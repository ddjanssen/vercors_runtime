package vct.col.ast.lang

import vct.col.ast.{JavaInvocation, Type}
import vct.col.resolve.{BuiltinInstanceMethod, RefADTFunction, RefFunction, RefInstanceFunction, RefInstanceMethod, RefInstancePredicate, RefJavaMethod, RefModelAction, RefModelProcess, RefPredicate, RefProcedure}

trait JavaInvocationImpl[G] { this: JavaInvocation[G] =>
  override def t: Type[G] = ref.get match {
    case RefFunction(decl) => decl.returnType
    case RefProcedure(decl) => decl.returnType
    case RefPredicate(decl) => decl.returnType
    case RefInstanceFunction(decl) => decl.returnType
    case RefInstanceMethod(decl) => decl.returnType
    case RefInstancePredicate(decl) => decl.returnType
    case RefADTFunction(decl) => decl.returnType
    case RefModelProcess(decl) => decl.returnType
    case RefModelAction(decl) => decl.returnType
    case RefJavaMethod(decl) => decl.returnType
    case BuiltinInstanceMethod(f) => f(obj.get)(arguments).t
  }
}