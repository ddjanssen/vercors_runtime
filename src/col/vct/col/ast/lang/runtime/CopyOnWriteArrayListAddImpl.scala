package vct.col.ast.lang.runtime

import vct.col.ast._
import vct.col.print._

trait CopyOnWriteArrayListAddImpl[G] {
  this: CopyOnWriteArrayListAdd[G] =>

  override def t: Type[G] = TBool[G]()

  override def layout(implicit ctx: Ctx): Doc =
    obj.show <> Text(".add(") <> arg.show <> Text(")")

}