/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.features.confluent

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}

import org.locationtech.geomesa.features.SerializationOption.SerializationOption
import org.locationtech.geomesa.features.SimpleFeatureSerializer.LimitedSerialization
import org.locationtech.geomesa.features.{ScalaSimpleFeature, SimpleFeatureSerializer}
import org.locationtech.geomesa.utils.cache.SoftThreadLocal
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

object ConfluentFeatureSerializer {
  def builder(sft: SimpleFeatureType): Builder = new Builder(sft)

  class Builder private [AvroFeatureSerializer] (sft: SimpleFeatureType)
      extends SimpleFeatureSerializer.Builder[Builder] {
    override def build(): AvroFeatureSerializer = new AvroFeatureSerializer(sft, options.toSet)
  }
}

/**
 * @param sft the simple feature type to encode
 * @param options the options to apply when encoding
 */
class AvroFeatureSerializer(sft: SimpleFeatureType, val options: Set[SerializationOption] = Set.empty)
    extends SimpleFeatureSerializer {

  private val writer = new AvroSimpleFeatureWriter(sft, options)
  private val reader = FeatureSpecificReader(sft, options)

  override def serialize(feature: SimpleFeature): Array[Byte] = {
    val out = AvroFeatureSerializer.outputs.getOrElseUpdate(new ByteArrayOutputStream())
    out.reset()
    serialize(feature, out)
    out.toByteArray
  }

  override def serialize(feature: SimpleFeature, out: OutputStream): Unit = {
    val encoder = AvroFeatureSerializer.encoder(out)
    writer.write(feature, encoder)
    encoder.flush()
  }

  override def deserialize(in: InputStream): SimpleFeature = reader.read(null, AvroFeatureSerializer.decoder(in))

  override def deserialize(bytes: Array[Byte]): SimpleFeature = deserialize(bytes, 0, bytes.length)

  override def deserialize(bytes: Array[Byte], offset: Int, length: Int): SimpleFeature =
    reader.read(null, AvroFeatureSerializer.decoder(bytes, offset, length))

  override def deserialize(id: String, in: InputStream): SimpleFeature = {
    val feature = deserialize(in)
    feature.asInstanceOf[ScalaSimpleFeature].setId(id) // TODO cast??
    feature
  }

  override def deserialize(id: String, bytes: Array[Byte], offset: Int, length: Int): SimpleFeature = {
    val feature = deserialize(bytes, offset, length)
    feature.asInstanceOf[ScalaSimpleFeature].setId(id) // TODO cast??
    feature
  }
}

/**
 * @param original the simple feature type that was encoded
 * @param projected the simple feature type to project to when decoding
 * @param options the options what were applied when encoding
 */
class ProjectingAvroFeatureDeserializer(original: SimpleFeatureType, projected: SimpleFeatureType,
                                        val options: Set[SerializationOption] = Set.empty)
    extends SimpleFeatureSerializer with LimitedSerialization {

  private val reader = FeatureSpecificReader(original, projected, options)

  override def serialize(feature: SimpleFeature): Array[Byte] =
    throw new NotImplementedError("This instance only handles deserialization")

  override def deserialize(bytes: Array[Byte]): SimpleFeature = decode(new ByteArrayInputStream(bytes))

  private var reuse: BinaryDecoder = _

  def decode(is: InputStream): SimpleFeature = {
    reuse = DecoderFactory.get().directBinaryDecoder(is, reuse)
    reader.read(null, reuse)
  }
}

/**
 * @param sft the simple feature type to decode
 * @param options the options what were applied when encoding
 */
@deprecated("Replaced with AvroFeatureSerializer")
class AvroFeatureDeserializer(sft: SimpleFeatureType, options: Set[SerializationOption] = Set.empty)
    extends AvroFeatureSerializer(sft, options)

