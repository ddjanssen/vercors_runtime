import scala.meta._

case class ColHelperAllScopes(info: ColDescription) extends ColHelperMaker {
  def make(): List[(String, List[Stat])] = List("AllScopes" -> q"""
    import vct.col.util.Scopes
    import scala.reflect.ClassTag
    import scala.util.Try
    import vct.result.VerificationError.SystemError

    case object AllScopes {
      case class InconsistentSuccessionTypes(left: Declaration[_], right: Declaration[_]) extends SystemError {
        override def text: String = {
          val leftMessage = context[vct.col.util.CurrentRewriteProgramContext].map {
            ctx => left.bareMessageInContext(ctx.program, "The kind of this declaration does not match ...")
          } getOrElse left.o.bareMessageInContext("The kind of this declaration does not match ...")

          val rightMessage = right.toString + Origin.HR + "... the kind of this declaration, so it may not be succeeded by this declaration."

          Origin.BOLD_HR + leftMessage + Origin.HR + rightMessage + Origin.BOLD_HR
        }
      }
    }

    case class AllScopes[Pre, Post]() {
      class AllFrozenScopes extends SuccessorsProvider[Pre, Post] {
        override def equals(obj: scala.Any): Boolean = obj match {
          case other: AllFrozenScopes =>
            ${ColDefs.DECLARATION_KINDS.map(decl => q"${ColDefs.scopes(decl)} == other.${ColDefs.scopes(decl)}").reduce[Term] { case (l, r) => q"$l && $r" } }
          case _ => false
        }

        ..${ColDefs.DECLARATION_KINDS.map(decl => q"""
          val ${Pat.Var(ColDefs.scopes(decl))} = AllScopes.this.${ColDefs.scopes(decl)}.freeze
        """).toList}

        ..${ColDefs.DECLARATION_KINDS.map(decl => q"""
          def computeSucc(decl: ${Type.Name(decl)}[Pre]): Option[${Type.Name(decl)}[Post]] =
            ${ColDefs.scopes(decl)}.computeSucc(decl)
        """).toList}

        ..${ColDefs.DECLARATION_KINDS.map(decl => q"""
          override def succ[RefDecl <: Declaration[Post]](decl: ${Type.Name(decl)}[Pre])(implicit tag: ClassTag[RefDecl]): Ref[Post, RefDecl] =
            ${ColDefs.scopes(decl)}.succ(decl)
        """).toList}
      }

      def freeze: AllFrozenScopes = new AllFrozenScopes

      def anyDeclare[T <: Declaration[Post]](decl1: T): T =
        ${ColHelperUtil.NonemptyMatch("decl declare kind cases", q"decl1", ColDefs.DECLARATION_KINDS.map(decl =>
          Case(p"decl: ${Type.Name(decl)}[Post]", None, q"${ColDefs.scopes(decl)}.declare(decl); decl1")
        ).toList)}

      def anySucceedOnly[T <: Declaration[Post]](pre1: Declaration[Pre], post1: T)(implicit tag: ClassTag[T]): T =
        ${ColHelperUtil.NonemptyMatch("decl succeed kind cases", q"(pre1, post1)", ColDefs.DECLARATION_KINDS.map(decl =>
          Case(p"(pre: ${Type.Name(decl)}[Pre], post: ${Type.Name(decl)}[Post])", None, q"${ColDefs.scopes(decl)}.succeedOnly(pre, post); post1")
        ).toList :+ Case(p"(pre, post)", None, q"throw AllScopes.InconsistentSuccessionTypes(pre, post)"))}

      ..${ColDefs.DECLARATION_KINDS.map(decl => q"""
        val ${Pat.Var(ColDefs.scopes(decl))}: Scopes[Pre, Post, ${Type.Name(decl)}[Pre], ${Type.Name(decl)}[Post]] = Scopes()
      """).toList}

      ..${ColDefs.DECLARATION_KINDS.map(decl => q"""
        def succ[RefDecl <: ${Type.Name(decl)}[Post]](decl: ${Type.Name(decl)}[Pre])(implicit tag: ClassTag[RefDecl]): Ref[Post, RefDecl] =
          ${ColDefs.scopes(decl)}.freeze.succ(decl)
      """).toList}
    }
  """.stats)
}