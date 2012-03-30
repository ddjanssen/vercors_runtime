package silAST.expressions.terms

import silAST.programs.NodeFactory
import silAST.source.SourceLocation
import silAST.symbols.logical.quantification.LogicalVariable
import collection.mutable.HashSet
import silAST.domains.DomainFunction
import silAST.expressions.util.{DTermSequence, GTermSequence}
import silAST.types.{booleanType, DataTypeFactory, DataType}


trait DTermFactory extends NodeFactory with GTermFactory with DataTypeFactory {
/*
  /////////////////////////////////////////////////////////////////////////
  override def migrate(t : Term)
  {
    t match {
      case dt : DTerm => migrate(dt)
      case _ => throw new Exception("Tried to migrate invalid expression " + t.toString)
    }
  }
  */
  /////////////////////////////////////////////////////////////////////////
  protected[silAST] def migrate(t : DTerm)
  {
    if (terms contains t)
      return;
    t match
    {
      case gt : GTerm => super.migrate(gt)
      case lv : LogicalVariableTerm => 
      {
        require(boundVariables contains lv.variable)
        addTerm(lv)
      }
      case fa : DDomainFunctionApplicationTerm =>
      {
        require(domainFunctions contains fa.function)
        fa.arguments.foreach(migrate(_))
        addTerm(fa)
      }
      case itet : DIfThenElseTerm => {
        require(itet.condition.dataType == booleanType)
        migrate(itet.condition)
        migrate(itet.pTerm)
        migrate(itet.nTerm)
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////
  def validBoundVariableName(name:String) : Boolean =
    name!="this"

  /////////////////////////////////////////////////////////////////////////
  def makeBoundVariable(name: String, dataType: DataType)(sourceLocation : SourceLocation): LogicalVariable = {
    require(dataTypes contains dataType)
    require(validBoundVariableName(name))
    val result: LogicalVariable = new LogicalVariable(name, dataType)(sourceLocation)
    boundVariables += result
    result
  }

  /////////////////////////////////////////////////////////////////////////
  def makeBoundVariableTerm(v: LogicalVariable)(sourceLocation : SourceLocation): LogicalVariableTerm = {
    require(boundVariables contains v)
    addTerm(new LogicalVariableTerm(v)(sourceLocation))
  }

  /////////////////////////////////////////////////////////////////////////
  def makeDDomainFunctionApplicationTerm(f: DomainFunction, a: DTermSequence)(sourceLocation : SourceLocation): DDomainFunctionApplicationTerm = {
    require(a != null)
    require(a.forall(_ != null))
    require(a.forall(terms contains _))
    require(domainFunctions contains f)

    a match {
      case a: GTermSequence => makeGDomainFunctionApplicationTerm(f, a)(sourceLocation)
      case _ => addTerm(new DDomainFunctionApplicationTermC(f, a)(sourceLocation))
    }
  }

  /////////////////////////////////////////////////////////////////////////
  def makeDIfThenElseTerm(c : DTerm,  p:DTerm,  n : DTerm)(sourceLocation : SourceLocation): DIfThenElseTerm = {
    migrate(c)
    migrate(p)
    migrate(n)
    require(c.dataType == booleanType)
    (c, p, n) match {
      case (gc:GTerm, gp:GTerm,gn:GTerm) => makeGIfThenElseTerm(gc,gp,gn)(sourceLocation)
      case _ => addTerm(new DIfThenElseTermC(c,p,n)(sourceLocation))
    }
  }

  /////////////////////////////////////////////////////////////////////////
  protected[silAST] val boundVariables = new HashSet[LogicalVariable]
}