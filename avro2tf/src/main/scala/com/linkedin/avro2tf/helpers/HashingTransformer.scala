package com.linkedin.avro2tf.helpers

import com.linkedin.avro2tf.configs.{Combiner, HashInfo}
import com.linkedin.avro2tf.jobs.TensorizeIn
import com.linkedin.avro2tf.parsers.TensorizeInParams
import com.linkedin.avro2tf.utils.Constants._
import com.linkedin.avro2tf.utils.{CommonUtils, HashingUtils}

import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row}

/**
 * A Hashing Transformer for the Feature Transformation job
 *
 */
object HashingTransformer {

  /**
   * The main function to perform hashing transformation
   *
   * @param dataFrame Input data Spark DataFrame
   * @param params TensorizeIn parameters specified by user
   * @return A Spark DataFrame
   */
  def hashTransform(dataFrame: DataFrame, params: TensorizeInParams): DataFrame = {

    val colsHashInfo = TensorizeInConfigHelper.getColsHashInfo(params)

    val hashedColumns = colsHashInfo.map {
      case (columnName, hashInfo) =>
        val dataType = dataFrame.schema(columnName).dataType

        val hashedColumn = if (CommonUtils.isArrayOfNTV(dataType)) {
          hashArrayOfNTVColumn(hashInfo)(dataFrame(columnName))
        } else if (CommonUtils.isArrayOfString(dataType) || CommonUtils.isArrayOfNumericalType(dataType)) {
          hashArrayOfPrimitiveColumn(hashInfo)(dataFrame(columnName))
        } else if (dataType.isInstanceOf[NumericType] || dataType.isInstanceOf[StringType]) {
          hashScalarColumn(hashInfo)(dataFrame(columnName))
        } else {
          throw new IllegalArgumentException(s"The type of $columnName column: ${dataType.typeName} is not supported")
        }

        hashedColumn.name(columnName)
    }

    val oldColumns = dataFrame.columns
      .filter(colName => !colsHashInfo.contains(colName))
      .map(dataFrame(_))

    dataFrame.select(oldColumns ++ hashedColumns: _*)
  }

  /**
   * Construct a Spark UDF to perform hashing on an array of NTV
   *
   * @param hashInfo Hashing info specified by user
   * @return A spark UDF
   */
  private def hashArrayOfNTVColumn(hashInfo: HashInfo): UserDefinedFunction = {

    udf {
      ntvs: Seq[Row] => {
        val idValues = if (ntvs == null || ntvs.isEmpty) {

          // if bag is empty put a dummy one with id as the unknown Id (last id), in hashing case, the num of hash buckets
          Seq(TensorizeIn.IdValue(hashInfo.hashBucketSize, 0))
        } else {
          {
            ntvs.flatMap {
              ntv => {
                val name = ntv.getAs[String](NTV_NAME)
                val term = ntv.getAs[String](NTV_TERM)
                val value = ntv.getAs[Float](NTV_VALUE)

                HashingUtils.multiHash(s"$name,$term", hashInfo.numHashFunctions, hashInfo.hashBucketSize)
                  .map(id => TensorizeIn.IdValue(id, value))
              }
            }.groupBy(_.id).map {
              group => {
                val idValue = group._2.reduce {
                  (a, b) => {
                    hashInfo.combiner match {
                      case Combiner.SUM | Combiner.AVG => a.copy(value = a.value + b.value)
                      case Combiner.MAX => a.copy(value = scala.math.max(a.value, b.value))
                    }
                  }
                }

                if (hashInfo.combiner == Combiner.AVG) {
                  idValue.copy(value = idValue.value / group._2.size)
                } else {
                  idValue
                }
              }
            }
          }.toSeq
        }

        TensorizeIn.SparseVector(idValues.map(_.id), idValues.map(_.value))
      }
    }
  }

  /**
   * Construct a Spark UDF to perform hashing on an array of primitive values
   *
   * @param hashInfo Hashing info specified by user
   * @return A spark UDF
   */
  private def hashArrayOfPrimitiveColumn(hashInfo: HashInfo): UserDefinedFunction = {

    udf {
      seqValues: Seq[Any] => {
        if (seqValues == null || seqValues.isEmpty) {
          Seq(hashInfo.hashBucketSize)
        } else {
          seqValues
            .flatMap(
              value => HashingUtils
                .multiHash(value.toString, hashInfo.numHashFunctions, hashInfo.hashBucketSize))
        }
      }
    }
  }

  /**
   * Construct a Spark UDF to perform hashing on a scalar value
   *
   * @param hashInfo Hashing info specified by user
   * @return A spark UDF
   */
  private def hashScalarColumn(hashInfo: HashInfo): UserDefinedFunction = {

    udf {
      scalarValue: Any => {
        if (scalarValue == null) {
          Seq(hashInfo.hashBucketSize)
        } else {
          HashingUtils.multiHash(scalarValue.toString, hashInfo.numHashFunctions, hashInfo.hashBucketSize)
        }
      }
    }
  }
}