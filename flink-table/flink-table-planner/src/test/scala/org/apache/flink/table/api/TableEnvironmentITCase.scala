/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.api

import org.apache.flink.api.common.typeinfo.Types.STRING
import org.apache.flink.api.scala._
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala.{StreamExecutionEnvironment => ScalaStreamExecutionEnvironment}
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment
import org.apache.flink.table.api.bridge.scala.{StreamTableEnvironment => ScalaStreamTableEnvironment, _}
import org.apache.flink.table.api.config.TableConfigOptions
import org.apache.flink.table.api.internal.{TableEnvironmentImpl, TableEnvironmentInternal}
import org.apache.flink.table.catalog._
import org.apache.flink.table.planner.factories.utils.TestCollectionTableFactory
import org.apache.flink.table.planner.runtime.utils.TestingAppendSink
import org.apache.flink.table.planner.utils.{TableTestUtil, TestTableSourceSinks, TestTableSourceWithTime}
import org.apache.flink.table.planner.utils.TableTestUtil.{readFromResource, replaceStageId}
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}
import org.apache.flink.testutils.junit.utils.TempDirUtils
import org.apache.flink.types.{Row, RowKind}
import org.apache.flink.util.{CollectionUtil, FileUtils}

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir

import java.io.{File, FileFilter}
import java.lang.{Long => JLong}
import java.nio.file.Path
import java.util

import scala.collection.mutable

@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class TableEnvironmentITCase(tableEnvName: String, isStreaming: Boolean) {

  @TempDir
  var tempFolder: Path = _

  var tEnv: TableEnvironment = _

  private val settings = if (isStreaming) {
    EnvironmentSettings.newInstance().inStreamingMode().build()
  } else {
    EnvironmentSettings.newInstance().inBatchMode().build()
  }

  @BeforeEach
  def setup(): Unit = {
    tableEnvName match {
      case "TableEnvironment" =>
        tEnv = TableEnvironmentImpl.create(settings)
      case "StreamTableEnvironment" =>
        tEnv = StreamTableEnvironment.create(
          StreamExecutionEnvironment.getExecutionEnvironment,
          settings)
      case _ => throw new UnsupportedOperationException("unsupported tableEnvName: " + tableEnvName)
    }
    TestTableSourceSinks.createPersonCsvTemporaryTable(tEnv, "MyTable")
  }

  @TestTemplate
  def testExecuteTwiceUsingSameTableEnv(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink2")

    checkEmptyFile(sink1Path)
    checkEmptyFile(sink2Path)

    val table1 = tEnv.sqlQuery("select first from MyTable")
    table1.executeInsert("MySink1").await()
    assertFirstValues(sink1Path)
    checkEmptyFile(sink2Path)

    // delete first csv file
    new File(sink1Path).delete()
    assertFalse(new File(sink1Path).exists())

    val table2 = tEnv.sqlQuery("select last from MyTable")
    table2.executeInsert("MySink2").await()
    assertFalse(new File(sink1Path).exists())
    assertLastValues(sink2Path)
  }

  @TestTemplate
  def testExplainAndExecuteSingleSink(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val table1 = tEnv.sqlQuery("select first from MyTable")
    table1.executeInsert("MySink1").await()
    assertFirstValues(sinkPath)
  }

  @TestTemplate
  def testExecuteSqlWithInsertInto(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyFile(sinkPath)
    val tableResult = tEnv.executeSql("insert into MySink1 select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink1")
    assertFirstValues(sinkPath)
  }

  @TestTemplate
  def testExecuteSqlWithInsertOverwrite(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }

    val sinkPath = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sinkPath',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val tableResult1 = tEnv.executeSql("insert overwrite MySink select first from MyTable")
    checkInsertTableResult(tableResult1, "default_catalog.default_database.MySink")
    assertFirstValues(sinkPath)

    val tableResult2 = tEnv.executeSql("insert overwrite MySink select first from MyTable")
    checkInsertTableResult(tableResult2, "default_catalog.default_database.MySink")
    assertFirstValues(sinkPath)
  }

  @TestTemplate
  def testExecuteSqlAndExecuteInsert(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("last"), Array(STRING)),
      "MySink2")
    checkEmptyFile(sink1Path)
    checkEmptyFile(sink2Path)

    val tableResult = tEnv.executeSql("insert into MySink1 select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink1")

    assertFirstValues(sink1Path)
    checkEmptyFile(sink2Path)

    // delete first csv file
    new File(sink1Path).delete()
    assertFalse(new File(sink1Path).exists())

    tEnv.sqlQuery("select last from MyTable").executeInsert("MySink2").await()
    assertFalse(new File(sink1Path).exists())
    assertLastValues(sink2Path)
  }

  @TestTemplate
  def testExecuteSqlAndToDataStream(): Unit = {
    if (!tableEnvName.equals("StreamTableEnvironment")) {
      return
    }
    val streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
    val streamTableEnv = StreamTableEnvironment.create(streamEnv, settings)
    TestTableSourceSinks.createPersonCsvTemporaryTable(streamTableEnv, "MyTable")
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      streamTableEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyFile(sink1Path)

    val table = streamTableEnv.sqlQuery("select last from MyTable where id > 0")
    val resultSet = streamTableEnv.toAppendStream(table, classOf[Row])
    val sink = new TestingAppendSink
    resultSet.addSink(sink)

    val tableResult = streamTableEnv.executeSql("insert into MySink1 select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink1")
    assertFirstValues(sink1Path)

    // the DataStream program is not executed
    assertFalse(sink.isInitialized)

    deleteFile(sink1Path)

    streamEnv.execute("test2")
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)
    // the table program is not executed again
    assertFileNotExist(sink1Path)
  }

  @TestTemplate
  def testToDataStreamAndExecuteSql(): Unit = {
    if (!tableEnvName.equals("StreamTableEnvironment")) {
      return
    }
    val streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
    val streamTableEnv = StreamTableEnvironment.create(streamEnv, settings)
    TestTableSourceSinks.createPersonCsvTemporaryTable(streamTableEnv, "MyTable")
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      streamTableEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyFile(sink1Path)

    val table = streamTableEnv.sqlQuery("select last from MyTable where id > 0")
    val resultSet = streamTableEnv.toAppendStream(table, classOf[Row])
    val sink = new TestingAppendSink
    resultSet.addSink(sink)

    val insertStmt = "insert into MySink1 select first from MyTable"

    val explain = streamTableEnv.explainSql(insertStmt)
    assertEquals(
      replaceStageId(readFromResource("/explain/testSqlUpdateAndToDataStream.out")),
      replaceStageId(explain))

    assertEquals(
      replaceStageId(readFromResource("/explain/testSqlUpdateAndToDataStreamWithPlanAdvice.out")),
      replaceStageId(streamTableEnv.explainSql(insertStmt, ExplainDetail.PLAN_ADVICE))
    )

    streamEnv.execute("test2")
    // the table program is not executed
    checkEmptyFile(sink1Path)
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)

    streamTableEnv.executeSql(insertStmt).await()
    assertFirstValues(sink1Path)
    // the DataStream program is not executed again because the result in sink is not changed
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)
  }

  @TestTemplate
  def testFromToDataStreamAndExecuteSql(): Unit = {
    if (!tableEnvName.equals("StreamTableEnvironment")) {
      return
    }
    val streamEnv = ScalaStreamExecutionEnvironment.getExecutionEnvironment
    val streamTableEnv = ScalaStreamTableEnvironment.create(streamEnv, settings)
    val t = streamEnv
      .fromCollection(getPersonData)
      .toTable(streamTableEnv, 'first, 'id, 'score, 'last)
    streamTableEnv.createTemporaryView("MyTable", t)
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      streamTableEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyFile(sink1Path)

    val table = streamTableEnv.sqlQuery("select last from MyTable where id > 0")
    val resultSet = streamTableEnv.toAppendStream[Row](table)
    val sink = new TestingAppendSink
    resultSet.addSink(sink)

    val insertStmt = "insert into MySink1 select first from MyTable"

    val explain = streamTableEnv.explainSql(insertStmt)
    assertEquals(
      replaceStageId(readFromResource("/explain/testFromToDataStreamAndSqlUpdate.out")),
      replaceStageId(explain))

    assertEquals(
      replaceStageId(
        readFromResource("/explain/testFromToDataStreamAndSqlUpdateWithPlanAdvice.out")),
      replaceStageId(streamTableEnv.explainSql(insertStmt, ExplainDetail.PLAN_ADVICE))
    )

    streamEnv.execute("test2")
    // the table program is not executed
    checkEmptyFile(sink1Path)
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)

    streamTableEnv.executeSql(insertStmt).await()
    assertFirstValues(sink1Path)
    // the DataStream program is not executed again because the result in sink is not changed
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)
  }

  @TestTemplate
  def testExecuteInsert(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink")
    checkEmptyFile(sinkPath)
    val table = tEnv.sqlQuery("select first from MyTable")
    val tableResult = table.executeInsert("MySink")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink")
    assertFirstValues(sinkPath)
  }

  @TestTemplate
  def testExecuteInsert2(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink")
    checkEmptyFile(sinkPath)
    val tableResult = tEnv.executeSql("execute insert into MySink select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink")
    assertFirstValues(sinkPath)
  }

  @TestTemplate
  def testExecuteInsertOverwrite(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }
    val sinkPath = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sinkPath',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )
    val tableResult1 = tEnv.sqlQuery("select first from MyTable").executeInsert("MySink", true)
    checkInsertTableResult(tableResult1, "default_catalog.default_database.MySink")
    assertFirstValues(sinkPath)

    val tableResult2 = tEnv.sqlQuery("select first from MyTable").executeInsert("MySink", true)
    checkInsertTableResult(tableResult2, "default_catalog.default_database.MySink")
    assertFirstValues(sinkPath)
  }

  @TestTemplate
  def testTableDMLSync(): Unit = {
    tEnv.getConfig.set(TableConfigOptions.TABLE_DML_SYNC, Boolean.box(true))
    val sink1Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink1 (
         |  first string,
         |  last string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink1Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val sink2Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink2 (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink2Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val sink3Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink3 (
         |  last string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink3Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val tableResult1 =
      tEnv.sqlQuery("select first, last from MyTable").executeInsert("MySink1", false)

    val stmtSet = tEnv.createStatementSet()
    stmtSet.addInsertSql("INSERT INTO MySink2 select first from MySink1")
    stmtSet.addInsertSql("INSERT INTO MySink3 select last from MySink1")
    val tableResult2 = stmtSet.execute()

    // checkInsertTableResult will wait the job finished,
    // we should assert file values first to verify job has been finished
    assertFirstValues(sink2Path)
    assertLastValues(sink3Path)

    // check TableResult after verifying file values
    checkInsertTableResult(
      tableResult2,
      "default_catalog.default_database.MySink2",
      "default_catalog.default_database.MySink3")

    // Verify it's no problem to invoke await twice
    tableResult1.await()
    tableResult2.await()
  }

  @TestTemplate
  def testStatementSet(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("last"), Array(STRING)),
      "MySink2")

    val stmtSet = tEnv.createStatementSet()
    stmtSet
      .addInsert("MySink1", tEnv.sqlQuery("select first from MyTable"))
      .addInsertSql("insert into MySink2 select last from MyTable")

    val actual = stmtSet.explain()
    val expected = TableTestUtil.readFromResource("/explain/testStatementSet.out")
    assertEquals(replaceStageId(expected), replaceStageId(actual))

    if (isStreaming) {
      assertEquals(
        replaceStageId(
          TableTestUtil.readFromResource("/explain/testStatementSetWithPlanAdvice.out")),
        replaceStageId(stmtSet.explain(ExplainDetail.PLAN_ADVICE))
      )
    } else {
      assertThatThrownBy(() => stmtSet.explain(ExplainDetail.PLAN_ADVICE))
        .hasMessageContaining("EXPLAIN PLAN_ADVICE is not supported under batch mode.")
        .isInstanceOf[UnsupportedOperationException]

    }

    val tableResult = stmtSet.execute()
    checkInsertTableResult(
      tableResult,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues(sink1Path)
    assertLastValues(sink2Path)
  }

  @TestTemplate
  def testExecuteStatementSet(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("last"), Array(STRING)),
      "MySink2")

    val tableResult = tEnv.executeSql("""execute statement set begin
                                        |insert into MySink1 select first from MyTable;
                                        |insert into MySink2 select last from MyTable;
                                        |end""".stripMargin)
    checkInsertTableResult(
      tableResult,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues(sink1Path)
    assertLastValues(sink2Path)
  }

  @TestTemplate
  def testStatementSetWithOverwrite(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }
    val sink1Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink1 (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink1Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val sink2Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink2 (
         |  last string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink2Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val stmtSet = tEnv.createStatementSet()
    stmtSet.addInsert("MySink1", tEnv.sqlQuery("select first from MyTable"), true)
    stmtSet.addInsertSql("insert overwrite MySink2 select last from MyTable")

    val tableResult1 = stmtSet.execute()
    checkInsertTableResult(
      tableResult1,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues(sink1Path)
    assertLastValues(sink2Path)

    // execute again using same StatementSet instance
    stmtSet
      .addInsert("MySink1", tEnv.sqlQuery("select first from MyTable"), true)
      .addInsertSql("insert overwrite MySink2 select last from MyTable")

    val tableResult2 = stmtSet.execute()
    checkInsertTableResult(
      tableResult2,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues(sink1Path)
    assertLastValues(sink2Path)
  }

  @TestTemplate
  def testStatementSetWithSameSinkTableNames(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }
    val sinkPath = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sinkPath',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val stmtSet = tEnv.createStatementSet()
    stmtSet.addInsert("MySink", tEnv.sqlQuery("select first from MyTable"), true)
    stmtSet.addInsertSql("insert overwrite MySink select last from MyTable")

    val tableResult = stmtSet.execute()
    // only check the schema
    checkInsertTableResult(
      tableResult,
      "default_catalog.default_database.MySink_1",
      "default_catalog.default_database.MySink_2")
  }

  @TestTemplate
  def testExecuteSelect(): Unit = {
    val query = {
      """
        |select id, concat(concat(`first`, ' '), `last`) as `full name`
        |from MyTable where mod(id, 2) = 0
      """.stripMargin
    }
    testExecuteSelectInternal(query)
    val query2 = {
      """
        |execute select id, concat(concat(`first`, ' '), `last`) as `full name`
        |from MyTable where mod(id, 2) = 0
      """.stripMargin
    }
    testExecuteSelectInternal(query2)
  }

  def testExecuteSelectInternal(query: String): Unit = {
    val tableResult = tEnv.executeSql(query)
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(
      ResolvedSchema.of(
        Column.physical("id", DataTypes.INT()),
        Column.physical("full name", DataTypes.STRING())),
      tableResult.getResolvedSchema)
    val expected = util.Arrays.asList(
      Row.of(Integer.valueOf(2), "Bob Taylor"),
      Row.of(Integer.valueOf(4), "Peter Smith"),
      Row.of(Integer.valueOf(6), "Sally Miller"),
      Row.of(Integer.valueOf(8), "Kelly Williams")
    )
    val actual = CollectionUtil.iteratorToList(tableResult.collect())
    actual.sort(new util.Comparator[Row]() {
      override def compare(o1: Row, o2: Row): Int = {
        o1.getField(0).asInstanceOf[Int].compareTo(o2.getField(0).asInstanceOf[Int])
      }
    })
    assertEquals(expected, actual)
  }

  @TestTemplate
  def testExecuteSelectWithUpdateChanges(): Unit = {
    val tableResult = tEnv.sqlQuery("select count(*) as c from MyTable").execute()
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(
      ResolvedSchema.of(Column.physical("c", DataTypes.BIGINT().notNull())),
      tableResult.getResolvedSchema)
    val expected = if (isStreaming) {
      util.Arrays.asList(
        Row.ofKind(RowKind.INSERT, JLong.valueOf(1)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(1)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(2)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(2)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(3)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(3)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(4)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(4)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(5)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(5)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(6)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(6)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(7)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(7)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(8))
      )
    } else {
      util.Arrays.asList(Row.of(JLong.valueOf(8)))
    }
    val actual = CollectionUtil.iteratorToList(tableResult.collect())
    assertEquals(expected, actual)
  }

  @TestTemplate
  def testExecuteSelectWithTimeAttribute(): Unit = {
    val data = Seq("Mary")
    val schema = new TableSchema(Array("name", "pt"), Array(Types.STRING, Types.LOCAL_DATE_TIME))
    val sourceType = Types.STRING
    val tableSource = new TestTableSourceWithTime(true, schema, sourceType, data, null, "pt")
    // TODO refactor this after FLINK-16160 is finished
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal("T", tableSource)

    val tableResult = tEnv.executeSql("select * from T")
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(
      ResolvedSchema.of(
        Column.physical("name", DataTypes.STRING()),
        Column.physical("pt", DataTypes.TIMESTAMP_LTZ(3))),
      tableResult.getResolvedSchema)
    val it = tableResult.collect()
    assertTrue(it.hasNext)
    val row = it.next()
    assertEquals(2, row.getArity)
    assertEquals("Mary", row.getField(0))
    assertFalse(it.hasNext)
  }

  @TestTemplate
  def testClearOperation(): Unit = {
    TestCollectionTableFactory.reset()
    val tableEnv = TableEnvironmentImpl.create(settings)
    tableEnv.executeSql("create table dest1(x map<int,bigint>) with('connector' = 'COLLECTION')")
    tableEnv.executeSql("create table dest2(x int) with('connector' = 'COLLECTION')")
    tableEnv.executeSql("create table src(x int) with('connector' = 'COLLECTION')")

    try {
      // it would fail due to query and sink type mismatch
      tableEnv.executeSql("insert into dest1 select count(*) from src")
      fail("insert is expected to fail due to type mismatch")
    } catch {
      case _: Exception => // expected
    }

    tableEnv.executeSql("drop table dest1")
    tableEnv.executeSql("insert into dest2 select x from src").await()
  }

  def getPersonData: List[(String, Int, Double, String)] = {
    val data = new mutable.MutableList[(String, Int, Double, String)]
    data.+=(("Mike", 1, 12.3, "Smith"))
    data.+=(("Bob", 2, 45.6, "Taylor"))
    data.+=(("Sam", 3, 7.89, "Miller"))
    data.+=(("Peter", 4, 0.12, "Smith"))
    data.+=(("Liz", 5, 34.5, "Williams"))
    data.+=(("Sally", 6, 6.78, "Miller"))
    data.+=(("Alice", 7, 90.1, "Smith"))
    data.+=(("Kelly", 8, 2.34, "Williams"))
    data.toList
  }

  private def assertFirstValues(csvFilePath: String): Unit = {
    val expected = List("Mike", "Bob", "Sam", "Peter", "Liz", "Sally", "Alice", "Kelly")
    val actual = readFile(csvFilePath)
    assertEquals(expected.sorted, actual.sorted)
  }

  private def assertLastValues(csvFilePath: String): Unit = {
    val actual = readFile(csvFilePath)
    assertEquals(getExpectedLastValues.sorted, actual.sorted)
  }

  private def getExpectedLastValues: List[String] = {
    List("Smith", "Taylor", "Miller", "Smith", "Williams", "Miller", "Smith", "Williams")
  }

  private def checkEmptyFile(csvFilePath: String): Unit = {
    assertTrue(FileUtils.readFileUtf8(new File(csvFilePath)).isEmpty)
  }

  private def deleteFile(path: String): Unit = {
    new File(path).delete()
    assertFalse(new File(path).exists())
  }

  private def assertFileNotExist(path: String): Unit = {
    assertFalse(new File(path).exists())
  }

  private def checkInsertTableResult(tableResult: TableResult, fieldNames: String*): Unit = {
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(util.Arrays.asList(fieldNames: _*), tableResult.getResolvedSchema.getColumnNames)
    // return the result until the job is finished
    val it = tableResult.collect()
    assertTrue(it.hasNext)
    val affectedRowCounts = fieldNames.map(_ => JLong.valueOf(-1L))
    assertEquals(Row.of(affectedRowCounts: _*), it.next())
    assertFalse(it.hasNext)
  }

  private def readFile(csvFilePath: String): List[String] = {
    val file = new File(csvFilePath)
    if (file.isDirectory) {
      file
        .listFiles(new FileFilter() {
          override def accept(f: File): Boolean = f.isFile
        })
        .map(FileUtils.readFileUtf8)
        .flatMap(_.split("\n"))
        .toList
    } else {
      FileUtils.readFileUtf8(file).split("\n").toList
    }
  }

}

object TableEnvironmentITCase {
  @Parameters(name = "{0}:isStream={1}")
  def parameters(): util.Collection[Array[_]] = {
    util.Arrays.asList(
      Array("TableEnvironment", true),
      Array("TableEnvironment", false),
      Array("StreamTableEnvironment", true)
    )
  }
}
