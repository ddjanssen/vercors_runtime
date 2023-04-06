import $file.common
import $file.fetchJars

import mill._
import modules.Jvm

import common.Dir
import fetchJars.{antlr => antlrJar}

trait GenModule extends Module {
  def base = T { Dir.src / "parsers" / "antlr4" }

  def lexer: String
  def lexerRef: T[PathRef] = T { PathRef(base() / lexer) }

  def parser: String
  def parserRef: T[PathRef] = T { PathRef(base() / parser) }

  def deps: Seq[String]
  def depsRef: T[Seq[PathRef]] = T { deps.map(dep => base() / dep).map(PathRef(_)) }

  def generate = T {
    def runAntlr(target: os.Path, args: Seq[String] = Nil): Unit = {
      val mainArgs = Seq(
        "-encoding", "utf-8",
        "-package", "vct.antlr4.generated",
        "-lib", base().toString,
        "-o", T.dest.toString,
        target.toString
      ) ++ args

      Jvm.runSubprocess(
        mainClass = "org.antlr.v4.Tool",
        classPath = Agg(antlrJar.classPath().path),
        mainArgs = mainArgs
      )
    }

    depsRef()
    runAntlr(lexerRef().path)
    runAntlr(parserRef().path, args = Seq("-listener", "-visitor", "-scala-extractor-objects"))
    PathRef(T.dest)
  }
}

object c extends GenModule {
  def lexer = "LangCLexer.g4"
  def parser = "CParser.g4"
  def deps = Seq(
    "SpecParser.g4", "SpecLexer.g4",
    "LangCParser.g4", "LangCLexer.g4",
    "LangOMPParser.g4", "LangOMPLexer.g4",
    "LangGPGPUParser.g4", "LangGPGPULexer.g4",
  )
}

object java extends GenModule {
  def lexer = "LangJavaLexer.g4"
  def parser = "JavaParser.g4"
  def deps = Seq(
    "SpecParser.g4", "SpecLexer.g4",
    "LangJavaParser.g4", "LangJavaLexer.g4",
  )
}

object pvl extends GenModule {
  def lexer = "LangPVLLexer.g4"
  def parser = "PVLParser.g4"
  def deps = Seq(
    "SpecParser.g4", "SpecLexer.g4",
    "LangPVLParser.g4", "LangPVLLexer.g4",
  )
}