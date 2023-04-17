package vct.col.ast.family.bipdata

import vct.col.ast.BipOutgoingData
import vct.col.print._

trait BipOutgoingDataImpl[G] { this: BipOutgoingData[G] =>
  override def layout(implicit ctx: Ctx): Doc =
    Doc.stack(Seq(
      if(pure) Text("@Pure") else Empty,
      Text("@Data(name =") <+> ctx.name(this) <> ")",
      Text("public") <+> t <+> ctx.name(this) <> "()" <+> body.layoutAsBlock,
    ))
}