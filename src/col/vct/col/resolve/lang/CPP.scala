package vct.col.resolve.lang

import hre.util.FuncTools
import vct.col.ast._
import vct.col.origin._
import vct.col.resolve._
import vct.col.resolve.ctx._
import vct.col.typerules.Types
import vct.result.VerificationError.UserError

import scala.collection.immutable.Seq

case object CPP {
  implicit private val o: Origin = DiagnosticOrigin

  case class CPPTypeNotSupported(node: Option[Node[_]]) extends UserError {
    override def code: String = "cppTypeNotSupported"

    override def text: String = {
      (node match {
        case Some(node) => node.o.messageInContext(_)
        case None => (text: String) => text
      })("This type is not supported by VerCors.")
    }
  }

  val NUMBER_LIKE_PREFIXES: Seq[Seq[CPPDeclarationSpecifier[_]]] = Seq(
    Nil,
    Seq(CPPUnsigned()),
    Seq(CPPSigned()),
  )

  val NUMBER_LIKE_TYPES: Seq[Seq[CPPDeclarationSpecifier[_]]] = Seq(
    Seq(CPPInt()),
    Seq(CPPLong()),
    Seq(CPPLong(), CPPInt()),
    Seq(CPPLong(), CPPLong()),
    Seq(CPPLong(), CPPLong(), CPPInt()),
  )

  val NUMBER_LIKE_SPECIFIERS: Seq[Seq[CPPDeclarationSpecifier[_]]] =
    for (prefix <- NUMBER_LIKE_PREFIXES; t <- NUMBER_LIKE_TYPES)
      yield prefix ++ t

  case class DeclaratorInfo[G](params: Option[Seq[CPPParam[G]]], typeOrReturnType: Type[G] => Type[G], name: String)

  def getDeclaratorInfo[G](decl: CPPDeclarator[G]): DeclaratorInfo[G] = decl match {
   // EW TODO: split parameters into normal and lambda params
    case CPPAddressingDeclarator(operators, inner) =>
      // EW TODO check if allowed
      val innerInfo = getDeclaratorInfo(inner)
      if (operators.size == 1 && operators.head.isInstanceOf[CPPReference[G]]) {
        // Pass by reference, so & can be ignored
        DeclaratorInfo(
          innerInfo.params,
          t => innerInfo.typeOrReturnType(t),
          innerInfo.name)
      } else if (operators.collectFirst({ case x: CPPReference[G] => x }).isDefined) {
        // Do not support multiple &, or & later in the sequence
        throw CPPTypeNotSupported(None)
      } else {
        DeclaratorInfo(
          innerInfo.params,
          t => innerInfo.typeOrReturnType(FuncTools.repeat[Type[G]](TPointer(_), operators.size, t)),
          innerInfo.name)
      }
    case array @ CPPArrayDeclarator(inner, size) =>
      val innerInfo = getDeclaratorInfo(inner)
      DeclaratorInfo(innerInfo.params, t => innerInfo.typeOrReturnType(CPPTArray(size, t)(array.blame)), innerInfo.name)
    case CPPTypedFunctionDeclarator(params, _, inner) =>
      val innerInfo = getDeclaratorInfo(inner)
      DeclaratorInfo(params = Some(params), typeOrReturnType = t => t, innerInfo.name)
    case CPPLambdaDeclarator(params) =>
      val filteredParams = Seq()
      val lambdaParams = Seq()
      DeclaratorInfo(params = Some(params), typeOrReturnType = _ => TVoid(), "")
    case CPPName(name) => DeclaratorInfo(params = None, typeOrReturnType = (t => t), name)
  }

  def filterOutLambdaParams[G](params: Seq[CPPParam[G]]): Seq[CPPParam[G]] =
    params.filterNot(param => getPrimitiveType(param.specifiers).isInstanceOf[CPPTLambda[G]])

  def getPrimitiveType[G](specs: Seq[CPPDeclarationSpecifier[G]], context: Option[Node[G]] = None): Type[G] =
    specs.collect { case spec: CPPTypeSpecifier[G] => spec } match {
      case Seq(CPPVoid()) => TVoid()
      case Seq(CPPChar()) => TChar()
      case t if CPP.NUMBER_LIKE_SPECIFIERS.contains(t) => TInt()
      case Seq(CPPSpecificationType(t@TFloat(_, _))) => t
      case Seq(CPPBool()) => TBool()
      case Seq(SYCLClass("event", None)) => SYCLTEvent()
      case Seq(SYCLClass("handler", None)) => SYCLTHandler()
      case Seq(SYCLClass("queue", None)) => SYCLTQueue()
      case Seq(SYCLClass("item", Some(dim))) => SYCLTItem(dim)
      case Seq(SYCLClass("nd_item", Some(dim))) => SYCLTNDItem(dim)
      case Seq(SYCLClass("range", Some(dim))) => SYCLTRange(dim)
      case Seq(SYCLClass("nd_range", Some(dim))) => SYCLTNDRange(dim)
      case Seq(CPPTypedefName("VERCORS::LAMBDA", _)) => CPPTLambda()
      case Seq(defn@CPPTypedefName(_, _)) => Types.notAValue(defn.ref.get)
      case Seq(CPPSpecificationType(typ)) => typ
      case spec +: _ => throw CPPTypeNotSupported(context.orElse(Some(spec)))
      case _ => throw CPPTypeNotSupported(context)
    }

  def nameFromDeclarator(declarator: CPPDeclarator[_]): String =
    getDeclaratorInfo(declarator).name

  def typeOrReturnTypeFromDeclaration[G](specs: Seq[CPPDeclarationSpecifier[G]], decl: CPPDeclarator[G]): Type[G] =
    getDeclaratorInfo(decl).typeOrReturnType(CPPPrimitiveType(specs))

  def paramsFromDeclarator[G](declarator: CPPDeclarator[G]): Seq[CPPParam[G]] =
    getDeclaratorInfo(declarator).params.get

  def findCPPTypeName[G](name: String, ctx: TypeResolutionContext[G]): Option[CPPTypeNameTarget[G]] = {
    if (name == "VERCORS::LAMBDA") {
      return Some(RefCPPLambda[G](CPPLambdaRef()))
    }
    ctx.stack.flatten.collectFirst {
      case target: CPPTypeNameTarget[G] if target.name == name => target
    }
  }

  def replacePotentialClassmemberName[G](name: String, ctx: ReferenceResolutionContext[G]): String = {
    if (name.contains('.') && name.count(x => x == '.') == 1) {
      // Class method, replace with SYCL equivalent
      val classVarName = name.split('.').head

      // Get type (so class) of variable (instance)
      val classTarget = ctx.stack.flatten.collectFirst {
        case target: CPPNameTarget[G] if target.name == classVarName => target
      }
      val className = classTarget match {
        case Some(RefCPPLocalDeclaration(decl, _)) => Some(getPrimitiveType(decl.decl.specs))
        case Some(RefCPPGlobalDeclaration(decl, _)) => Some(getPrimitiveType(decl.decl.specs))
        case Some(RefCPPParam(decl)) => Some(getPrimitiveType(decl.specifiers))
        case _ => None
      }
      // Replace class reference name to a namespace name
      if (className.isDefined) {
        // Remove generic type part, e.g. 'item<3>' becomes 'item'
        val newClassName = className.get.toString.replaceFirst("<\\d>$", "")
        return name.replace(classVarName + ".", newClassName + "::")
      }
    }
    name
  }

  def findCPPName[G](name: String, genericArg: Option[Int], ctx: ReferenceResolutionContext[G]): Seq[CPPNameTarget[G]] = {
    val targetName: String = replacePotentialClassmemberName(name, ctx)

    var targets = ctx.stack.flatten.collect {
      case target: CPPNameTarget[G] if target.name == targetName => target
    }

    if (targets.isEmpty && !name.endsWith("::constructor")) {
      // Not a known method, so search for constructor
      targets = findCPPName(name + "::constructor", genericArg, ctx)
    }
    targets
  }


  def findForwardDeclaration[G](declarator: CPPDeclarator[G], ctx: ReferenceResolutionContext[G]): Option[RefCPPGlobalDeclaration[G]] =
    ctx.stack.flatten.collectFirst {
      case target: RefCPPGlobalDeclaration[G] if target.name == nameFromDeclarator(declarator) => target
    }

  // For stub methods, only find a definition in the same scope.
  def findDefinition[G](declarator: CPPDeclarator[G], ctx: ReferenceResolutionContext[G]): Option[RefCPPFunctionDefinition[G]] = {
    if (declarator.isInstanceOf[CPPTypedFunctionDeclarator[G]] && ctx.currentResult.isDefined) {
      // declarator refers to a stub method
      val scopeLevel = ctx.stack.indexWhere(stack => stack.contains(ctx.currentResult.get))
      if (scopeLevel > -1) {
        ctx.stack(scopeLevel).collectFirst {
          case target: RefCPPFunctionDefinition[G] if target.name == nameFromDeclarator(declarator) => target
        }
      } else {
        None
      }
    } else {
      ctx.stack.flatten.collectFirst {
        case target: RefCPPFunctionDefinition[G] if target.name == nameFromDeclarator(declarator) => target
      }
    }
  }

  def checkArgs[G](params: Seq[Type[G]], args: Seq[Expr[G]]): Boolean = {
    if (params.size != args.size) return false
    !params.indices.exists(i => {
      val argType = args(i).t match {
        case value: CPPPrimitiveType[G] => getPrimitiveType(value.specifiers)
        case _ => args(i).t
      }
      params(i) != argType && !(params(i).isInstanceOf[TInt[G]] && argType.isInstanceOf[TBoundedInt[G]])
    })
  }

  def getParamTypes[G](ref: CPPInvocationTarget[G]): Seq[Type[G]] = ref match {
    case globalDeclRef: RefCPPGlobalDeclaration[G] if globalDeclRef.decls.decl.inits.size == 1 =>
      paramsFromDeclarator(globalDeclRef.decls.decl.inits.head.decl).map(param => getPrimitiveType(param.specifiers))
    case functionDeclRef: RefCPPFunctionDefinition[G] =>
      paramsFromDeclarator(functionDeclRef.decl.declarator).map(param => getPrimitiveType(param.specifiers))
    case functionRef: RefFunction[G] => functionRef.decl.args.map(variable => variable.t)
    case procedureRef: RefProcedure[G] => procedureRef.decl.args.map(variable => variable.t)
    case predicateRef: RefPredicate[G] => predicateRef.decl.args.map(variable => variable.t)
    case instanceFunctionRef: RefInstanceFunction[G] => instanceFunctionRef.decl.args.map(variable => variable.t)
    case instanceMethodRef: RefInstanceMethod[G] => instanceMethodRef.decl.args.map(variable => variable.t)
    case instancePredicateRef: RefInstancePredicate[G] => instancePredicateRef.decl.args.map(variable => variable.t)
    case aDTFunctionRef: RefADTFunction[G] => aDTFunctionRef.decl.args.map(variable => variable.t)
    case modelProcessRef: RefModelProcess[G] => modelProcessRef.decl.args.map(variable => variable.t)
    case modelActionRef: RefModelAction[G] => modelActionRef.decl.args.map(variable => variable.t)
    case proverFunctionRef: RefProverFunction[G] => proverFunctionRef.decl.args.map(variable => variable.t)
    case _ => Seq()
  }

  def resolveInvocation[G](obj: Expr[G], args: Seq[Expr[G]], ctx: ReferenceResolutionContext[G]): CPPInvocationTarget[G] =
    obj.t match {
      case t: TNotAValue[G] => t.decl.get match {
        // Do not check arguments for BuiltinInstanceMethods as we do not know the argument types
        case target: BuiltinInstanceMethod[G] => target
        // The previously found method is already the correct one
        case target: CPPInvocationTarget[G] if checkArgs(getParamTypes(target), args) => target
        case _ if obj.isInstanceOf[CPPLocal[G]] =>
          // Currently linked method does not have correct params
          // So find all declarations with correct name and see if there is
          // an alternative whose parameters do match the arguments
          val local = obj.asInstanceOf[CPPLocal[G]]
          for (decl <- findCPPName(local.name, local.genericArg, ctx)) {
            decl match {
              case value: CPPInvocationTarget[G] =>
                if (checkArgs(getParamTypes(value), args)) {
                  local.ref = Some(decl)
                  t.decl = Some(decl)
                  return value
                }
              case _ =>
            }
          }
          throw NotApplicable(obj)
        case _ => throw NotApplicable(obj)
      }
      case _ => throw NotApplicable(obj)
    }
}
