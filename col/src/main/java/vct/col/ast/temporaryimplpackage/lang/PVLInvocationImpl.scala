package vct.col.ast.temporaryimplpackage.lang

import vct.col.ast.{PVLInvocation, TProcess, TResource, Type}
import vct.col.resolve.{BuiltinInstanceMethod, RefADTFunction, RefFunction, RefInstanceFunction, RefInstanceMethod, RefInstancePredicate, RefModelAction, RefModelProcess, RefPredicate, RefProcedure}

trait PVLInvocationImpl { this: PVLInvocation =>
  override def t: Type = ref.get match {
    case RefFunction(decl) => decl.returnType.particularize(decl.typeArgs.zip(typeArgs).toMap)
    case RefProcedure(decl) => decl.returnType
    case RefPredicate(_) => TResource()
    case RefInstanceFunction(decl) => decl.returnType.particularize(decl.typeArgs.zip(typeArgs).toMap)
    case RefInstanceMethod(decl) => decl.returnType
    case RefInstancePredicate(_) => TResource()
    case RefADTFunction(decl) => decl.returnType
    case RefModelProcess(_) => TProcess()
    case RefModelAction(_) => TProcess()
    case BuiltinInstanceMethod(f) => f(obj.get)(args).t
  }
}