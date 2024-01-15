package vct.col.ast.lang

import vct.col.ast._
import vct.col.print._

trait ThreadIdImpl[G] {
  this: ThreadId[G] =>


  override def t: Type[G] = TInt()

  def objectLayout(implicit ctx: Ctx) : Doc = {
    obj match {
      case Some(value) => value.show <> Text(".threadId()")
      case None => Text("Thread.currentThread().threadId()")
    }
  }


  override def layout(implicit ctx: Ctx): Doc = objectLayout
}