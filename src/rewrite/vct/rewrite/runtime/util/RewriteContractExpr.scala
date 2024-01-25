package vct.rewrite.runtime.util

import hre.util.ScopedStack
import vct.col.ast._
import vct.col.origin.Origin
import vct.col.rewrite.{Generation, Rewriter, Rewritten}
import vct.rewrite.runtime.util.permissionTransfer.PermissionData
import vct.col.util.AstBuildHelpers._
import vct.result.VerificationError.Unreachable
import vct.rewrite.runtime.CreatePredicates
import vct.rewrite.runtime.util.AbstractQuantifierRewriter.LoopBodyContent
import vct.rewrite.runtime.util.PermissionRewriter.permissionToRuntimeValueRewrite
import vct.rewrite.runtime.util.Util._

import scala.collection.mutable


case class RewriteContractExpr[Pre <: Generation](pd: PermissionData[Pre])(implicit program: Program[Pre]) extends AbstractQuantifierRewriter[Pre](pd) {
  override val allScopes = pd.outer.allScopes

  override def dispatchLoopBody(loopBodyContent: LoopBodyContent[Pre])(implicit origin: Origin): Block[Post] = createAssertions(loopBodyContent.expr)

  def createAssertions(expr: Expr[Pre]): Block[Post] = {
    implicit val origin: Origin = expr.o
    val unfoldedExpr = unfoldStar(expr)
    Block[Post](unfoldedExpr.map(dispatchExpr))
  }

  private def dispatchExpr(e: Expr[Pre]): Statement[Post] = {
    implicit val origin: Origin = e.o
    e match {
      case _: Star[Pre] => createAssertions(e)
      case p: Perm[Pre] => dispatchPermission(p)
      case ipa: InstancePredicateApply[Pre] => dispatchInstancePredicateApply(ipa)
      case _: Starall[Pre] | _: Exists[Pre] | _: Forall[Pre] => {
        super.dispatchQuantifier(e)
      }
      case _ => Assert[Post](super.dispatch(e))(null)
    }
  }

  private def dispatchPermission(p: Perm[Pre])(implicit origin: Origin = p.o): Block[Post] = {
    val permissionLocation: PermissionLocation[Pre] = FindPermissionLocation[Pre](pd).getPermission(p)(origin)
    val cond = permissionToRuntimeValueRewrite(p)
    val assertion = Assert[Post](permissionLocation.get() === cond)(null)
    Block[Post](Seq(assertion))
  }

  private def dispatchInstancePredicateApply(ipa: InstancePredicateApply[Pre]) : Block[Post] = {
    implicit val origin: Origin = ipa.o
    val ipd: InstancePredicateData[Pre] = findInstancePredicateData(ipa)
    val mi = ipd.createMethodInvocation(CreatePredicates.GETPREDICATE)
    val dispatchedMI = super.dispatch(mi)
    val newAssign = Assert[Post](dispatchedMI !== Null[Post]())(null)
    Block[Post](Seq(newAssign))
  }

}
