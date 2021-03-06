package com.outr.giantscala

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.youi.Unique

class Id[T <: ModelObject[T]](val value: String) extends AnyVal {
  override def toString: String = s"Id($value)"
}

object Id {
  def apply[T <: ModelObject[T]]: Id[T] = new Id[T](Unique())

  implicit def encoder[T <: ModelObject[T]]: Encoder[Id[T]] = new Encoder[Id[T]] {
    override def apply(id: Id[T]): Json = Json.fromString(id.value)
  }
  implicit def decoder[T <: ModelObject[T]]: Decoder[Id[T]] = new Decoder[Id[T]] {
    override def apply(c: HCursor): Result[Id[T]] = Right(new Id[T](c.value.asString.get))
  }

  def apply[T <: ModelObject[T]](id: String): Id[T] = new Id[T](id)
}