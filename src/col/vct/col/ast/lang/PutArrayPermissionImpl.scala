package vct.col.ast.lang

import vct.col.ast._
import vct.col.print._

trait PutArrayPermissionImpl[G] {
  this: PutArrayPermission[G] =>

  override def t: Type[G] = TFraction[G]()

  override def layout(implicit ctx: Ctx): Doc =
    Nest(objectLocation.show <> Text(s".__runtime__$id.get(") <> location <> Text(").put(Thread.currentThread().getId(), ") <> permission <> Text(")"))
}