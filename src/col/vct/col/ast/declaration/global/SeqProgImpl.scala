package vct.col.ast.declaration.global

import vct.col.ast.{Class, Declaration, SeqProg}
import vct.col.ast.util.Declarator
import vct.col.check.{CheckContext, CheckError}
import vct.col.origin.Origin
import vct.col.print._

trait SeqProgImpl[G] { this: SeqProg[G] =>
  def members: Seq[Declaration[G]] = threads ++ Seq(run) ++ decls
  override def declarations: Seq[Declaration[G]] = args ++ members

  override def layout(implicit ctx: Ctx): Doc =
    Doc.stack(Seq(
      contract,
      Group(Text("seq_program") <+> ctx.name(this) <> "(" <> Doc.args(args) <> ")") <+> "{" <>>
        Doc.stack(threads ++ decls :+ run) <+/>
      "}"
    ))
}
