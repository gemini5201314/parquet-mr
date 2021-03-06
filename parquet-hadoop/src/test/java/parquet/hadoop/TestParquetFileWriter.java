/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.hadoop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static parquet.column.Encoding.BIT_PACKED;
import static parquet.column.Encoding.PLAIN;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.junit.Test;

import parquet.Log;
import parquet.bytes.BytesInput;
import parquet.column.ColumnDescriptor;
import parquet.column.page.Page;
import parquet.column.page.PageReadStore;
import parquet.column.page.PageReader;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.hadoop.metadata.ParquetMetadata;
import parquet.schema.MessageType;
import parquet.schema.MessageTypeParser;

public class TestParquetFileWriter {
  private static final Log LOG = Log.getLog(TestParquetFileWriter.class);

  @Test
  public void testWriteRead() throws Exception {

    File testFile = new File("target/test/TestParquetFileWriter/testParquetFile").getAbsoluteFile();
    testFile.delete();

    Path path = new Path(testFile.toURI());
    Configuration configuration = new Configuration();

    MessageType schema = MessageTypeParser.parseMessageType("message m { required group a {required binary b;} required group c { required int64 d; }}");
    String[] path1 = {"a", "b"};
    ColumnDescriptor c1 = schema.getColumnDescription(path1);
    String[] path2 = {"c", "d"};
    ColumnDescriptor c2 = schema.getColumnDescription(path2);

    byte[] bytes1 = { 0, 1, 2, 3};
    byte[] bytes2 = { 1, 2, 3, 4};
    byte[] bytes3 = { 2, 3, 4, 5};
    byte[] bytes4 = { 3, 4, 5, 6};
    CompressionCodecName codec = CompressionCodecName.UNCOMPRESSED;
    ParquetFileWriter w = new ParquetFileWriter(configuration, schema, path);
    w.start();
    w.startBlock(3);
    w.startColumn(c1, 5, codec);
    w.writeDataPage(2, 4, BytesInput.from(bytes1), BIT_PACKED, BIT_PACKED, PLAIN);
    w.writeDataPage(3, 4, BytesInput.from(bytes1), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.startColumn(c2, 6, codec);
    w.writeDataPage(2, 4, BytesInput.from(bytes2), BIT_PACKED, BIT_PACKED, PLAIN);
    w.writeDataPage(3, 4, BytesInput.from(bytes2), BIT_PACKED, BIT_PACKED, PLAIN);
    w.writeDataPage(1, 4, BytesInput.from(bytes2), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.endBlock();
    w.startBlock(4);
    w.startColumn(c1, 7, codec);
    w.writeDataPage(7, 4, BytesInput.from(bytes3), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.startColumn(c2, 8, codec);
    w.writeDataPage(8, 4, BytesInput.from(bytes4), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.endBlock();
    w.end(new HashMap<String, String>());

    ParquetMetadata readFooter = ParquetFileReader.readFooter(configuration, path);
    assertEquals("footer: "+readFooter, 2, readFooter.getBlocks().size());

    { // read first block of col #1
      ParquetFileReader r = new ParquetFileReader(configuration, path, Arrays.asList(readFooter.getBlocks().get(0)), Arrays.asList(schema.getColumnDescription(path1)));
      PageReadStore pages = r.readNextRowGroup();
      assertEquals(3, pages.getRowCount());
      validateContains(schema, pages, path1, 2, BytesInput.from(bytes1));
      validateContains(schema, pages, path1, 3, BytesInput.from(bytes1));
      assertNull(r.readNextRowGroup());
    }

    { // read all blocks of col #1 and #2

      ParquetFileReader r = new ParquetFileReader(configuration, path, readFooter.getBlocks(), Arrays.asList(schema.getColumnDescription(path1), schema.getColumnDescription(path2)));

      PageReadStore pages = r.readNextRowGroup();
      assertEquals(3, pages.getRowCount());
      validateContains(schema, pages, path1, 2, BytesInput.from(bytes1));
      validateContains(schema, pages, path1, 3, BytesInput.from(bytes1));
      validateContains(schema, pages, path2, 2, BytesInput.from(bytes2));
      validateContains(schema, pages, path2, 3, BytesInput.from(bytes2));
      validateContains(schema, pages, path2, 1, BytesInput.from(bytes2));

      pages = r.readNextRowGroup();
      assertEquals(4, pages.getRowCount());

      validateContains(schema, pages, path1, 7, BytesInput.from(bytes3));
      validateContains(schema, pages, path2, 8, BytesInput.from(bytes4));

      assertNull(r.readNextRowGroup());
    }
    PrintFooter.main(new String[] {path.toString()});
  }

  @Test
  public void testMetaDataFile() throws Exception {

    File testDir = new File("target/test/TestParquetFileWriter/testMetaDataFileDir").getAbsoluteFile();

    Path testDirPath = new Path(testDir.toURI());
    Configuration configuration = new Configuration();

    final FileSystem fs = testDirPath.getFileSystem(configuration);
    fs.delete(testDirPath, true);
    fs.mkdirs(testDirPath);

    MessageType schema = MessageTypeParser.parseMessageType("message m { required group a {required binary b;} required group c { required int64 d; }}");
    createFile(configuration, new Path(testDirPath, "part0"), schema);
    createFile(configuration, new Path(testDirPath, "part1"), schema);
    createFile(configuration, new Path(testDirPath, "part2"), schema);

    FileStatus outputStatus = fs.getFileStatus(testDirPath);
    List<Footer> footers = ParquetFileReader.readAllFootersInParallel(configuration, outputStatus);
    validateFooters(footers);
    ParquetFileWriter.writeSummaryFile(configuration, testDirPath, footers);

    footers = ParquetFileReader.readFooters(configuration, outputStatus);
    validateFooters(footers);
    footers = ParquetFileReader.readFooters(configuration, fs.getFileStatus(new Path(testDirPath, "part0")));
    assertEquals(1, footers.size());

    final FileStatus metadataFile = fs.getFileStatus(new Path(testDirPath, ParquetFileWriter.PARQUET_METADATA_FILE));
    final List<Footer> metadata = ParquetFileReader.readSummaryFile(configuration, metadataFile);

    validateFooters(metadata);

    footers = ParquetFileReader.readAllFootersInParallelUsingSummaryFiles(configuration, Arrays.asList(fs.listStatus(testDirPath, new PathFilter() {
      @Override
      public boolean accept(Path p) {
        return !p.getName().startsWith("_");
      }
    })));
    validateFooters(footers);

    fs.delete(metadataFile.getPath(), false);

    footers = ParquetFileReader.readAllFootersInParallelUsingSummaryFiles(configuration, Arrays.asList(fs.listStatus(testDirPath)));
    validateFooters(footers);

  }

  private void validateFooters(final List<Footer> metadata) {
    LOG.debug(metadata);
    assertEquals(3, metadata.size());
    for (Footer footer : metadata) {
     final File file = new File(footer.getFile().toUri());
     assertTrue(file.getName(), file.getName().startsWith("part"));
     assertTrue(file.getPath(), file.exists());
     final ParquetMetadata parquetMetadata = footer.getParquetMetadata();
     assertEquals(2, parquetMetadata.getBlocks().size());
     assertEquals("bar", parquetMetadata.getKeyValueMetaData().get("foo"));
     assertEquals(footer.getFile().getName(), parquetMetadata.getKeyValueMetaData().get(footer.getFile().getName()));
    }
  }


  private void createFile(Configuration configuration, Path path, MessageType schema) throws IOException {
    String[] path1 = {"a", "b"};
    ColumnDescriptor c1 = schema.getColumnDescription(path1);
    String[] path2 = {"c", "d"};
    ColumnDescriptor c2 = schema.getColumnDescription(path2);

    byte[] bytes1 = { 0, 1, 2, 3};
    byte[] bytes2 = { 1, 2, 3, 4};
    byte[] bytes3 = { 2, 3, 4, 5};
    byte[] bytes4 = { 3, 4, 5, 6};
    CompressionCodecName codec = CompressionCodecName.UNCOMPRESSED;
    ParquetFileWriter w = new ParquetFileWriter(configuration, schema, path);
    w.start();
    w.startBlock(3);
    w.startColumn(c1, 5, codec);
    w.writeDataPage(2, 4, BytesInput.from(bytes1), BIT_PACKED, BIT_PACKED, PLAIN);
    w.writeDataPage(3, 4, BytesInput.from(bytes1), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.startColumn(c2, 6, codec);
    w.writeDataPage(2, 4, BytesInput.from(bytes2), BIT_PACKED, BIT_PACKED, PLAIN);
    w.writeDataPage(3, 4, BytesInput.from(bytes2), BIT_PACKED, BIT_PACKED, PLAIN);
    w.writeDataPage(1, 4, BytesInput.from(bytes2), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.endBlock();
    w.startBlock(4);
    w.startColumn(c1, 7, codec);
    w.writeDataPage(7, 4, BytesInput.from(bytes3), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.startColumn(c2, 8, codec);
    w.writeDataPage(8, 4, BytesInput.from(bytes4), BIT_PACKED, BIT_PACKED, PLAIN);
    w.endColumn();
    w.endBlock();
    final HashMap<String, String> extraMetaData = new HashMap<String, String>();
    extraMetaData.put("foo", "bar");
    extraMetaData.put(path.getName(), path.getName());
    w.end(extraMetaData);
  }

  private void validateContains(MessageType schema, PageReadStore pages, String[] path, int values, BytesInput bytes) throws IOException {
    PageReader pageReader = pages.getPageReader(schema.getColumnDescription(path));
    Page page = pageReader.readPage();
    assertEquals(values, page.getValueCount());
    assertArrayEquals(bytes.toByteArray(), page.getBytes().toByteArray());
  }

}
