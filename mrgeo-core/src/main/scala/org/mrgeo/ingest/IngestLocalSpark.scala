package org.mrgeo.ingest

import java.io.Externalizable
import java.util.Properties

import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
import org.apache.hadoop.mapreduce.{InputFormat, Job}
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.storage.StorageLevel
import org.mrgeo.data.DataProviderFactory
import org.mrgeo.data.DataProviderFactory.AccessMode
import org.mrgeo.data.ingest.ImageIngestDataProvider
import org.mrgeo.data.raster.{RasterUtils, RasterWritable}
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.utils.{SparkUtils, HadoopUtils}

class IngestLocalSpark extends IngestImageSpark with Externalizable {

  override def execute(context: SparkContext): Boolean = {

    val input = inputs(0) // there is only 1 input here...

    val format = new SequenceFileInputFormat[TileIdWritable, RasterWritable]

    val job: Job = Job.getInstance(HadoopUtils.createConfiguration())


    val rawtiles = context.sequenceFile(input, classOf[TileIdWritable], classOf[RasterWritable])

    // this is stupid, but because the way hadoop input formats may reuse the key/value objects,
    // if we don't do this, all the data will eventually collapse into a single entry.
    val mapped = rawtiles.map(tile => {
      (new TileIdWritable(tile._1), RasterWritable.toWritable(RasterWritable.toRaster(tile._2)))
    })

    val mergedTiles = mapped.reduceByKey((r1, r2) => {
      val src = RasterWritable.toRaster(r1)
      val dst = RasterUtils.makeRasterWritable(RasterWritable.toRaster(r2))

      val nodatas = Array.ofDim[Double](src.getNumBands)
      for (x <- nodatas.indices) {
        nodatas(x) = nodata.doubleValue()
      }

      RasterUtils.mosaicTile(src, dst, nodatas)
      RasterWritable.toWritable(dst)

    }).persist(StorageLevel.MEMORY_AND_DISK)


    val idp = DataProviderFactory.getMrsImageDataProvider(output, AccessMode.OVERWRITE, providerproperties)

    val raster = RasterWritable.toRaster(mergedTiles.first()._2)
    val nodatas = Array.ofDim[Double](raster.getNumBands)
    for (x <- nodatas.indices) {
      nodatas(x) = nodata.doubleValue()
    }

    SparkUtils.saveMrsPyramid(mergedTiles, idp, output, zoom, tilesize, nodatas, context.hadoopConfiguration,
      bounds = this.bounds, bands = this.bands, tiletype = this.tiletype,
      protectionlevel = this.protectionlevel, providerproperties = this.providerproperties)


    mergedTiles.unpersist()
    true
  }

}