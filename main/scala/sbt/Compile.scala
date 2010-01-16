/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah
 */
package sbt

import java.io.File
import xsbt.{AnalyzingCompiler, CompileFailed, CompilerArguments, ScalaInstance}

object CompileOrder extends Enumeration
{
	val Mixed, JavaThenScala, ScalaThenJava = Value
}

sealed abstract class CompilerCore
{
	final def apply(label: String, sources: Iterable[Path], classpath: Iterable[Path], outputDirectory: Path, scalaOptions: Seq[String], log: Logger): Option[String] =
		apply(label, sources, classpath, outputDirectory, scalaOptions, Nil, CompileOrder.Mixed, log)
	final def apply(label: String, sources: Iterable[Path], classpath: Iterable[Path], outputDirectory: Path, scalaOptions: Seq[String], javaOptions: Seq[String], order: CompileOrder.Value, log: Logger): Option[String] =
	{
			def filteredSources(extension: String) = sources.filter(_.asFile.getName.endsWith(extension))
			def fileSet(sources: Iterable[Path]) = Set() ++ sources.map(_.asFile)
			def process(label: String, sources: Iterable[_], act: => Unit) =
				() => if(sources.isEmpty) log.debug("No " + label + " sources.") else act

		val javaSources = fileSet(filteredSources(".java"))
		val scalaSources = fileSet( if(order == CompileOrder.Mixed) sources else filteredSources(".scala") )
		val classpathSet = fileSet(classpath)
		val scalaCompile = process("Scala", scalaSources, processScala(scalaSources, classpathSet, outputDirectory.asFile, scalaOptions, log) )
		val javaCompile = process("Java", javaSources, processJava(javaSources, classpathSet, outputDirectory.asFile, javaOptions, log))
		doCompile(label, sources, outputDirectory, order, log)(javaCompile, scalaCompile)
	}
	protected def doCompile(label: String, sources: Iterable[Path], outputDirectory: Path, order: CompileOrder.Value, log: Logger)(javaCompile: () => Unit, scalaCompile: () => Unit) =
	{
		log.info(actionStartMessage(label))
		if(sources.isEmpty)
		{
			log.info(actionNothingToDoMessage)
			None
		}
		else
		{
			FileUtilities.createDirectory(outputDirectory.asFile, log) orElse
				(try
				{
					val (first, second) = if(order == CompileOrder.JavaThenScala) (javaCompile, scalaCompile) else (scalaCompile, javaCompile)
					first()
					second()
					log.info(actionSuccessfulMessage)
					None
				}
				catch { case e: xsbti.CompileFailed => Some(e.toString) })
		}
	}
	def actionStartMessage(label: String): String
	def actionNothingToDoMessage: String
	def actionSuccessfulMessage: String
	protected def processScala(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], log: Logger): Unit
	protected def processJava(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], log: Logger): Unit
}

sealed abstract class CompilerBase extends CompilerCore
{
	def actionStartMessage(label: String) = "Compiling " + label + " sources..."
	val actionNothingToDoMessage = "Nothing to compile."
	val actionSuccessfulMessage = "Compilation successful."
}

// The following code is based on scala.tools.nsc.Main and scala.tools.nsc.ScalaDoc
// Copyright 2005-2008 LAMP/EPFL
// Original author: Martin Odersky

final class Compile(maximumErrors: Int, compiler: AnalyzingCompiler, analysisCallback: AnalysisCallback, baseDirectory: Path) extends CompilerBase
{
	protected def processScala(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], log: Logger)
	{
		val callbackInterface = new AnalysisInterface(analysisCallback, baseDirectory, outputDirectory)
		compiler(Set() ++ sources, Set() ++ classpath, outputDirectory, options, true, callbackInterface, maximumErrors, log)
	}
	protected def processJava(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], log: Logger)
	{
		val arguments = (new CompilerArguments(compiler.scalaInstance))(sources, classpath, outputDirectory, options, true)
		log.debug("Calling 'javac' with arguments:\n\t" + arguments.mkString("\n\t"))
		val code = Process("javac", arguments) ! log
		if( code != 0 ) throw new CompileFailed(arguments.toArray, "javac returned nonzero exit code")
	}
}
final class Scaladoc(maximumErrors: Int, compiler: AnalyzingCompiler) extends CompilerCore
{
	protected def processScala(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], log: Logger): Unit =
		compiler.doc(sources, classpath, outputDirectory, options, maximumErrors, log)
	protected def processJava(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], log: Logger) = ()

	def actionStartMessage(label: String) = "Generating API documentation for " + label + " sources..."
	val actionNothingToDoMessage = "No sources specified."
	val actionSuccessfulMessage = "API documentation generation successful."
	def actionUnsuccessfulMessage = "API documentation generation unsuccessful."
}
final class Console(compiler: AnalyzingCompiler) extends NotNull
{
	/** Starts an interactive scala interpreter session with the given classpath.*/
	def apply(classpath: Iterable[Path], log: Logger): Option[String] =
		apply(classpath, "", log)
	def apply(classpath: Iterable[Path], initialCommands: String, log: Logger): Option[String] =
	{
		def console0 = compiler.console(Set() ++ classpath.map(_.asFile), initialCommands, log)
		JLine.withJLine( Run.executeTrapExit(console0, log) )
	}
}

private final class AnalysisInterface(delegate: AnalysisCallback, basePath: Path, outputDirectory: File) extends xsbti.AnalysisCallback with NotNull
{
	val outputPath = Path.fromFile(outputDirectory)
	def superclassNames = delegate.superclassNames.toSeq.toArray[String]
	def superclassNotFound(superclassName: String) = delegate.superclassNotFound(superclassName)
	def beginSource(source: File) = delegate.beginSource(srcPath(source))
	def foundSubclass(source: File, subclassName: String, superclassName: String, isModule: Boolean) =
		delegate.foundSubclass(srcPath(source), subclassName, superclassName, isModule)
	def sourceDependency(dependsOn: File, source: File) =
		delegate.sourceDependency(srcPath(dependsOn), srcPath(source))
	def jarDependency(jar: File, source: File) = delegate.jarDependency(jar, srcPath(source))
	def generatedClass(source: File, clazz: File) = delegate.generatedClass(srcPath(source), classPath(clazz))
	def endSource(source: File) = delegate.endSource(srcPath(source))
	def foundApplication(source: File, className: String) = delegate.foundApplication(srcPath(source), className)
	def classDependency(clazz: File, source: File) =
	{
		val sourcePath = srcPath(source)
		Path.relativize(outputPath, clazz) match
		{
			case None =>  // dependency is a class file outside of the output directory
				delegate.classDependency(clazz, sourcePath)
			case Some(relativeToOutput) => // dependency is a product of a source not included in this compilation
				delegate.productDependency(relativeToOutput, sourcePath)
		}
	}
	def relativizeOrAbs(base: Path, file: File) = Path.relativize(base, file).getOrElse(Path.fromFile(file))
	def classPath(file: File) = relativizeOrAbs(outputPath, file)
	def srcPath(file: File) = relativizeOrAbs(basePath, file)
	def api(file: File, source: xsbti.api.Source) = delegate.api(srcPath(file), source)
}