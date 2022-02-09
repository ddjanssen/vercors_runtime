package viper.api

import hre.lang.HREExitException
import hre.lang.System.Warning
import hre.util.ScopedStack
import vct.col.ast.{AxiomaticDataType, PredicateApply, SplitAccountedPredicate, UnitAccountedPredicate}
import vct.col.origin.{AccountedDirection, FailLeft, FailRight}
import vct.col.ref.Ref
import vct.col.util.AstBuildHelpers.unfoldStar
import vct.col.{ast => col}
import vct.result.VerificationResult.SystemError
import viper.api.ColToSilver.NotSupported
import viper.silver.ast.TypeVar
import viper.silver.{ast => silver}

import scala.collection.immutable.{ListMap, SortedMap}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object ColToSilver {
  def transform(program: col.Program[_]): silver.Program =
    ColToSilver(program).transform()

  case class NotSupported(node: col.Node[_]) extends SystemError {
    override def text: String =
      node.o.messageInContext("This kind of node is not supported by silver directly. Is there a rewrite missing?")
  }
}

case class ColToSilver(program: col.Program[_]) {
  val domains: ArrayBuffer[silver.Domain] = ArrayBuffer()
  val fields: mutable.Map[col.SilverField[_], silver.Field] = mutable.Map()
  val functions: ArrayBuffer[silver.Function] = ArrayBuffer()
  val predicates: ArrayBuffer[silver.Predicate] = ArrayBuffer()
  val methods: ArrayBuffer[silver.Method] = ArrayBuffer()

  val nameStack: mutable.Stack[mutable.Map[col.Declaration[_], (String, Int)]] = mutable.Stack()
  var names: mutable.Map[col.Declaration[_], (String, Int)] = mutable.Map()
  val currentPredicatePath: ScopedStack[Seq[AccountedDirection]] = ScopedStack()

  def ??(node: col.Node[_]): Nothing =
    throw NotSupported(node)

  def push(): Unit = nameStack.push(names.clone())
  def pop(): Unit = names = nameStack.pop()

  def unpackName(name: String): (String, Int) = {
    val m = "^(.*?)([1-9][0-9]*)?$".r.findFirstMatchIn(name).get
    if(Option(m.group(2)).isDefined) {
      (m.group(1), Integer.parseInt(m.group(2)))
    } else {
      (m.group(1), 0)
    }
  }

  def packName(name: String, index: Int): String =
    if(index == 0) name
    else s"$name$index"

  /**
   * Give the declaration a silver-appropriate name that is as close as possible to the preferred name
   */
  def name(decl: col.Declaration[_]): String =
    if(names.contains(decl)) {
      ???
    } else {
      var (name, index) = unpackName(decl.o.preferredName)
      while(names.values.exists(_ == (name, index)) || silver.utility.Consistency.reservedNames.contains(packName(name, index))) {
        index += 1
      }
      names(decl) = (name, index)
      packName(name, index)
    }

  /**
   * Evaluate f within a new scope
   */
  def scoped[T](f: => T): T = {
    push()
    val result = f
    pop()
    result
  }

  /**
   * Name decl in the current scope, then evaluate f within a new scopegroupCount
   */
  def scoped[T](decl: col.Declaration[_])(f: => T): T = {
    name(decl)
    scoped(f)
  }

  /**
   * Retrieve the name for this reference
   */
  def ref[G](r: Ref[G, _ <: col.Declaration[G]]): String = ref(r.decl)

  /**
   * Retrieve the name for this declaration
   */
  def ref(decl: col.Declaration[_]): String =
    if(names.contains(decl)) {
      val (name, index) = names(decl)
      packName(name, index)
    } else {
      ???
    }

  def transform(): silver.Program = {
    program.declarations.foreach(name)
    program.declarations.collect { case adt: col.AxiomaticDataType[_] => adt }.flatMap(_.typeArgs).foreach(name)
    program.declarations.collect { case adt: col.AxiomaticDataType[_] => adt }.flatMap(_.decls).collect { case func: col.ADTFunction[_] => func }.foreach(name)
    program.declarations.collect { case field: col.SilverField[_] => field }.foreach(field => fields(field) = silver.Field(ref(field), typ(field.t))(info=NodeInfo(field)))
    program.declarations.foreach(collect)
    silver.Program(domains.toSeq, fields.values.toSeq, functions.toSeq, predicates.toSeq, methods.toSeq, extensions=Seq())()
  }

  def collect(decl: col.GlobalDeclaration[_]): Unit = decl match {
    case field: col.SilverField[_] =>
      // nop
    case rule: col.SimplificationRule[_] =>
      ??(rule)
    case function: col.Function[_] if !function.inline && function.typeArgs.isEmpty=>
      scoped {
        functions += silver.Function(
          ref(function),
          function.args.map(variable),
          typ(function.returnType),
          pred(function.contract.requires),
          pred(function.contract.ensures),
          function.body.map(exp),
        )(info=NodeInfo(function))
      }
    case procedure: col.Procedure[_] if procedure.returnType == col.TVoid() && !procedure.inline && !procedure.pure && procedure.typeArgs.isEmpty =>
      scoped {
        val labelDecls = procedure.body.toSeq.flatMap(_.transSubnodes.collect {
          case l: col.LabelDecl[_] => silver.Label(name(l), Seq())(info=NodeInfo(l))
        })
        methods += silver.Method(
          ref(procedure),
          procedure.args.map(variable),
          procedure.outArgs.map(variable),
          pred(procedure.contract.requires),
          pred(procedure.contract.ensures),
          procedure.body.map(body => silver.Seqn(Seq(block(body)), labelDecls)(info=NodeInfo(body)))
        )(info=NodeInfo(procedure))
      }
    case predicate: col.Predicate[_] if !predicate.inline && !predicate.threadLocal =>
      scoped {
        predicates += silver.Predicate(
          ref(predicate),
          predicate.args.map(variable),
          predicate.body.map(exp)
        )(info=NodeInfo(predicate))
      }
    case adt: col.AxiomaticDataType[_] =>
      domains += silver.Domain(
        name = ref(adt),
        typVars = adt.typeArgs.map(v => silver.TypeVar(ref(v))),
        functions = adt.decls.collect {
          case func: col.ADTFunction[_] =>
            silver.DomainFunc(ref(func), func.args.map(variable), typ(func.returnType), unique = false)(info=NodeInfo(func), domainName=ref(adt))
        },
        axioms = adt.decls.collect {
          case ax: col.ADTAxiom[_] =>
            silver.AnonymousDomainAxiom(exp(ax.axiom))(info=NodeInfo(ax), domainName=ref(adt))
        },
      )(info=NodeInfo(adt))
    case other =>
      ??(other)
  }

  def variable(v: col.Variable[_]): silver.LocalVarDecl =
    silver.LocalVarDecl(name(v), typ(v.t))(info=NodeInfo(v))

  def adtTypeArgs(adt: AxiomaticDataType[_]): Seq[TypeVar] =
    adt.typeArgs.map(v => silver.TypeVar(ref(v)))

  def typ(t: col.Type[_]): silver.Type = t match {
    case col.TBool() => silver.Bool
    case col.TInt() => silver.Int
    case col.TRational() => silver.Perm
    case col.TRef() => silver.Ref
    case col.TSeq(element) => silver.SeqType(typ(element))
    case col.TSet(element) => silver.SetType(typ(element))
    case col.TBag(element) => silver.MultisetType(typ(element))
    case col.TVar(Ref(v)) => silver.TypeVar(ref(v))
    case col.TAxiomatic(Ref(adt), args) =>
      val typeArgs = adtTypeArgs(adt)
      silver.DomainType(ref(adt), ListMap(typeArgs.zip(args.map(typ)) : _*))(typeArgs)
    case other => ??(other)
  }

  def pred(e: col.AccountedPredicate[_], path: Seq[AccountedDirection] = Nil): Seq[silver.Exp] = e match {
    case UnitAccountedPredicate(pred) => currentPredicatePath.having(path) { unfoldStar(pred).map(exp) }
    case SplitAccountedPredicate(left, right) => pred(left, path :+ FailLeft) ++ pred(right, path :+ FailRight)
  }

  def expInfo[T <: col.Expr[_]](e: T): NodeInfo[T] = {
    val result = NodeInfo(e)
    result.predicatePath = currentPredicatePath.topOption
    result
  }

  def exp(e: col.Expr[_]): silver.Exp = e match {
    case col.BooleanValue(value) => silver.BoolLit(value)(info=expInfo(e))
    case col.IntegerValue(value) => silver.IntLit(value)(info=expInfo(e))
    case col.SilverNull() => silver.NullLit()(info=expInfo(e))
    case col.Result(Ref(app)) => silver.Result(typ(app.returnType))(info=expInfo(e))

    case col.NoPerm() => silver.NoPerm()(info=expInfo(e))
    case col.ReadPerm() => silver.WildcardPerm()(info=expInfo(e))
    case col.WritePerm() => silver.FullPerm()(info=expInfo(e))

    case col.LiteralSeq(t, Nil) => silver.EmptySeq(typ(t))(info=expInfo(e))
    case col.LiteralSet(t, Nil) => silver.EmptySet(typ(t))(info=expInfo(e))
    case col.LiteralBag(t, Nil) => silver.EmptyMultiset(typ(t))(info=expInfo(e))
    case col.LiteralSeq(_, xs) => silver.ExplicitSeq(xs.map(exp))(info=expInfo(e))
    case col.LiteralSet(_, xs) => silver.ExplicitSet(xs.map(exp))(info=expInfo(e))
    case col.LiteralBag(_, xs) => silver.ExplicitMultiset(xs.map(exp))(info=expInfo(e))

    case col.Size(obj) => silver.SeqLength(exp(obj))(info=expInfo(e))

    case col.Exists(bindings, triggers, body) =>
      scoped { silver.Exists(bindings.map(variable), triggers.map(trigger), exp(body))(info=expInfo(e)) }
    case col.Forall(bindings, triggers, body) =>
      scoped { silver.Forall(bindings.map(variable), triggers.map(trigger), exp(body))(info=expInfo(e)) }
    case col.Starall(bindings, triggers, body) =>
      scoped { silver.Forall(bindings.map(variable), triggers.map(trigger), exp(body))(info=expInfo(e)) }
    case col.Let(binding, value, main) =>
      scoped { silver.Let(variable(binding), exp(value), exp(main))(info=expInfo(e)) }
    case col.Not(arg) => silver.Not(exp(arg))(info=expInfo(e))
    case col.And(left, right) => silver.And(exp(left), exp(right))(info=expInfo(e))
    case col.Star(left, right) => silver.And(exp(left), exp(right))(info=expInfo(e))
    case col.Implies(left, right) => silver.Implies(exp(left), exp(right))(info=expInfo(e))
    case col.Or(left, right) => silver.Or(exp(left), exp(right))(info=expInfo(e))

    case res @ col.Perm(col.SilverDeref(obj, Ref(field)), perm) =>
      val permValue = exp(perm)
      permValue.info.asInstanceOf[NodeInfo[_]].permissionValuePermissionNode = Some(res)
      silver.FieldAccessPredicate(silver.FieldAccess(exp(obj), fields(field))(info=NodeInfo(res)), permValue)(info=NodeInfo(res))
    case res: PredicateApply[_] =>
      val silver = pred(res)
      silver.perm.info.asInstanceOf[NodeInfo[_]].permissionValuePermissionNode = Some(res)
      silver
    case col.SilverCurPredPerm(p, args) => silver.CurrentPerm(silver.PredicateAccess(args.map(exp), ref(p))(info=expInfo(e)))(info=expInfo(e))
    case col.SilverCurFieldPerm(obj, field) => silver.CurrentPerm(silver.FieldAccess(exp(obj), fields(field.decl))(info=expInfo(e)))(info=expInfo(e))
    case col.Local(v) => silver.LocalVar(ref(v), typ(v.decl.t))(info=expInfo(e))
    case col.SilverDeref(obj, ref) => silver.FieldAccess(exp(obj), fields(ref.decl))(info=expInfo(e))
    case col.FunctionInvocation(f, args, Nil) =>
      silver.FuncApp(ref(f), args.map(exp))(silver.NoPosition, expInfo(e), typ(f.decl.returnType), silver.NoTrafos)
    case inv @ col.ADTFunctionInvocation(typeArgs, Ref(func), args) => typeArgs match {
      case Some((Ref(adt), typeArgs)) =>
        silver.DomainFuncApp(ref(func), args.map(exp), ListMap(adtTypeArgs(adt).zip(typeArgs.map(typ)) : _*))(silver.NoPosition, expInfo(e), typ(inv.t), ref(adt), silver.NoTrafos)
      case None => ??(inv)
    }
    case col.Unfolding(p: PredicateApply[_], body) => silver.Unfolding(pred(p), exp(body))(info=expInfo(e))
    case col.Select(condition, whenTrue, whenFalse) => silver.CondExp(exp(condition), exp(whenTrue), exp(whenFalse))(info=expInfo(e))
    case col.Old(expr, None) => silver.Old(exp(expr))(info=expInfo(e))
    case col.Old(expr, Some(lbl)) => silver.LabelledOld(exp(expr), ref(lbl))(info=expInfo(e))

    case col.UMinus(arg) => silver.Minus(exp(arg))(info=expInfo(e))
    case col.Plus(left, right) => silver.Add(exp(left), exp(right))(info=expInfo(e))
    case col.Minus(left, right) => silver.Sub(exp(left), exp(right))(info=expInfo(e))
    case col.Mult(left, right) => silver.Mul(exp(left), exp(right))(info=expInfo(e))
    case col.Div(left, right) => silver.PermDiv(exp(left), exp(right))(info=expInfo(e))
    case col.Mod(left, right) => silver.Mod(exp(left), exp(right))(info=expInfo(e))
    case col.FloorDiv(left, right) => silver.Div(exp(left), exp(right))(info=expInfo(e))

    case col.SilverIntToRat(col.NoPerm()) => silver.NoPerm()(info = expInfo(e))
    case col.SilverIntToRat(col.WritePerm()) => silver.FullPerm()(info = expInfo(e))
    case col.SilverIntToRat(perm) => silver.IntPermMul(exp(perm), silver.FullPerm()(info=expInfo(e)))(info=expInfo(e))

    case col.Eq(left, right) => silver.EqCmp(exp(left), exp(right))(info=expInfo(e))
    case col.Neq(left, right) => silver.NeCmp(exp(left), exp(right))(info=expInfo(e))
    case col.Greater(left, right) => silver.GtCmp(exp(left), exp(right))(info=expInfo(e))
    case col.Less(left, right) => silver.LtCmp(exp(left), exp(right))(info=expInfo(e))
    case col.GreaterEq(left, right) => silver.GeCmp(exp(left), exp(right))(info=expInfo(e))
    case col.LessEq(left, right) => silver.LeCmp(exp(left), exp(right))(info=expInfo(e))
    case col.SubSet(left, right) => silver.AnySetSubset(exp(left), exp(right))(info=expInfo(e))

    case subscript@col.SeqSubscript(seq, index) =>
      val silverIndex = exp(index)
      silverIndex.info.asInstanceOf[NodeInfo[_]].seqIndexSubscriptNode = Some(subscript)
      silver.SeqIndex(exp(seq), silverIndex)(info=expInfo(e))
    case col.Range(from, to) => silver.RangeSeq(exp(from), exp(to))(info=expInfo(e))
    case col.Drop(xs, count) => silver.SeqDrop(exp(xs), exp(count))(info=expInfo(e))
    case col.Take(xs, count) => silver.SeqTake(exp(xs), exp(count))(info=expInfo(e))
    case col.SeqUpdate(xs, i, x) => silver.SeqUpdate(exp(xs), exp(i), exp(x))(info=expInfo(e))
    case col.Concat(xs, ys) => silver.SeqAppend(exp(xs), exp(ys))(info=expInfo(e))
    case col.SetMember(x, xs) => silver.AnySetContains(exp(x), exp(xs))(info=expInfo(e))
    case col.SeqMember(x, xs) => silver.SeqContains(exp(x), exp(xs))(info=expInfo(e))
    case col.BagMemberCount(x, xs) => silver.AnySetContains(exp(x), exp(xs))(info=expInfo(e))
    case other => ??(other)
  }

  def trigger(patterns: Seq[col.Expr[_]]): silver.Trigger =
    silver.Trigger(patterns.map(exp))()

  def pred(p: col.PredicateApply[_]): silver.PredicateAccessPredicate =
    silver.PredicateAccessPredicate(silver.PredicateAccess(p.args.map(exp), ref(p.ref))(info=NodeInfo(p)), exp(p.perm))(info=NodeInfo(p))

  def acc(e: col.Expr[_]): silver.LocationAccess = e match {
    case col.PredicateApply(Ref(pred), args, _) => silver.PredicateAccess(args.map(exp), ref(pred))(info=NodeInfo(pred))
    case col.SilverDeref(obj, Ref(field)) => silver.FieldAccess(exp(obj), fields(field))(info=NodeInfo(e))
    case other => ??(other)
  }

  def stat(s: col.Statement[_]): silver.Stmt = s match {
    case inv@col.InvokeProcedure(method, args, outArgs, Nil) =>
      silver.MethodCall(ref(method), args.map(exp), outArgs.map(arg => silver.LocalVar(ref(arg), typ(arg.decl.t))()))(
        silver.NoPosition, NodeInfo(inv), silver.NoTrafos)
    case col.SilverFieldAssign(obj, field, value) =>
      silver.FieldAssign(silver.FieldAccess(exp(obj), fields(field.decl))(info=NodeInfo(s)), exp(value))(info=NodeInfo(s))
    case col.SilverLocalAssign(v, value) =>
      silver.LocalVarAssign(silver.LocalVar(ref(v), typ(v.decl.t))(info=NodeInfo(s)), exp(value))(info=NodeInfo(s))
    case col.Block(statements) => silver.Seqn(statements.map(stat), Seq())(info=NodeInfo(s))
    case col.Scope(locals, body) =>
      val silverLocals = locals.map(variable)
      silver.Seqn(Seq(stat(body)), silverLocals)(info=NodeInfo(s))
    case col.Branch(Seq((cond, whenTrue), (col.BooleanValue(true), whenFalse))) => silver.If(exp(cond), block(whenTrue), block(whenFalse))(info=NodeInfo(s))
    case col.Loop(col.Block(Nil), cond, col.Block(Nil), col.LoopInvariant(inv), body) =>
      silver.While(exp(cond), unfoldStar(inv).map(exp), block(body))(info=NodeInfo(s))
    case col.Label(decl, col.Block(Nil)) => silver.Label(ref(decl), Seq())(info=NodeInfo(s))
    case col.Goto(lbl) => silver.Goto(ref(lbl))(info=NodeInfo(s))
    case col.Return(col.Void()) => silver.Seqn(Nil, Nil)(info=NodeInfo(s))
    case col.Exhale(res) => silver.Exhale(exp(res))(info=NodeInfo(s))
    case col.Assert(assn) => silver.Assert(exp(assn))(info=NodeInfo(s))
    case col.Inhale(res) => silver.Inhale(exp(res))(info=NodeInfo(s))
    case col.Assume(assn) => silver.Assume(exp(assn))(info=NodeInfo(s))
    case col.Fold(p: col.PredicateApply[_]) => silver.Fold(pred(p))(info=NodeInfo(s))
    case col.Unfold(p: col.PredicateApply[_]) => silver.Unfold(pred(p))(info=NodeInfo(s))
    case col.SilverNewRef(v, fs) => silver.NewStmt(silver.LocalVar(ref(v), typ(v.decl.t))(), fs.map(ref => fields(ref.decl)))(info=NodeInfo(s))
    case other => ??(other)
  }

  def block(s: col.Statement[_]): silver.Seqn = stat(s) match {
    case seqn: silver.Seqn => seqn
    case other => silver.Seqn(Seq(other), Seq())(info=NodeInfo(s))
  }
}
