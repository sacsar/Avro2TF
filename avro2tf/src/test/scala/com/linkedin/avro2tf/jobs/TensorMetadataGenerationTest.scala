package com.linkedin.avro2tf.jobs

import java.io.{File, FileOutputStream, PrintWriter}

import scala.io.Source

import com.databricks.spark.avro._
import com.linkedin.avro2tf.parsers.TensorizeInJobParamsParser
import com.linkedin.avro2tf.utils.ConstantsForTest._
import com.linkedin.avro2tf.utils.TestUtil.removeWhiteSpace
import com.linkedin.avro2tf.utils.WithLocalSparkSession
import org.apache.commons.io.FileUtils
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

class TensorMetadataGenerationTest extends WithLocalSparkSession {

  @DataProvider(name = "testFilenamesProvider")
  def getTestFilenames: Array[Array[Object]] = {

    Array(
      Array(TENSORIZEIN_CONFIG_PATH_VALUE_SAMPLE, EXPECTED_TENSOR_METADATA_GENERATED_JSON_PATH_TEXT),
      Array(
        TENSORIZEIN_CONFIG_PATH_VALUE_SAMPLE_WITHOUT_INT_FEATURES,
        EXPECTED_TENSOR_METADATA_WITHOUT_INT_FEATURES_GENERATED_JSON_PATH_TEXT
      )
    )
  }

  /**
   * Test if the Tensor Metadata Generation job can finish successfully
   */
  @Test(dataProvider = "testFilenamesProvider")
  def testTensorMetadataGeneration(tensorizeInConfigPath: String, expectedTensorMetadataPath: String): Unit = {

    val tensorizeInConfigFile = new File(
      getClass.getClassLoader.getResource(tensorizeInConfigPath).getFile
    ).getAbsolutePath
    FileUtils.deleteDirectory(new File(WORKING_DIRECTORY_TENSOR_METADATA_GENERATION_TEXT))

    // Set up external feature list
    val externalFeatureListFullPath = s"$WORKING_DIRECTORY_TENSOR_METADATA_GENERATION_TEXT/$EXTERNAL_FEATURE_LIST_PATH_TEXT"
    new File(externalFeatureListFullPath).mkdirs()
    new PrintWriter(
      new FileOutputStream(
        s"$externalFeatureListFullPath/$EXTERNAL_FEATURE_LIST_FILE_NAME_TEXT",
        ENABLE_APPEND)) {
      write(SAMPLE_EXTERNAL_FEATURE_LIST)
      close()
    }

    val params = Array(
      INPUT_PATHS_NAME, INPUT_TEXT_FILE_PATHS,
      WORKING_DIRECTORY_NAME, WORKING_DIRECTORY_TENSOR_METADATA_GENERATION_TEXT,
      TENSORIZEIN_CONFIG_PATH_NAME, tensorizeInConfigFile,
      EXTERNAL_FEATURE_LIST_PATH_NAME, externalFeatureListFullPath
    )

    val dataFrame = session.read.avro(INPUT_TEXT_FILE_PATHS)
    val tensorizeInParams = TensorizeInJobParamsParser.parse(params)

    val dataFrameExtracted = (new FeatureExtraction).run(dataFrame, tensorizeInParams)
    val dataFrameTransformed = (new FeatureTransformation).run(dataFrameExtracted, tensorizeInParams)
    (new FeatureListGeneration).run(dataFrameTransformed, tensorizeInParams)
    (new TensorMetadataGeneration).run(dataFrameTransformed, tensorizeInParams)

    // Check if tensor metadata JSON file is correctly generated
    val expectedTensorMetadata = getClass.getClassLoader.getResource(expectedTensorMetadataPath).getFile
    assertEquals(
      removeWhiteSpace(Source.fromFile(tensorizeInParams.workingDir.tensorMetadataPath).mkString),
      removeWhiteSpace(Source.fromFile(expectedTensorMetadata).mkString)
    )
  }
}