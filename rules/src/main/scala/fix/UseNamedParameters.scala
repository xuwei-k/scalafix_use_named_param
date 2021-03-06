package fix

import metaconfig.Configured
import scalafix.v1._

import scala.annotation.tailrec
import scala.meta._

final class UseNamedParameters(config: UseNamedParametersConfig)
    extends SemanticRule(classOf[UseNamedParameters].getSimpleName) {
  def this() = this(UseNamedParametersConfig.default)

  override def withConfiguration(config: Configuration): Configured[Rule] =
    config.conf
      .getOrElse(this.getClass.getSimpleName)(this.config)
      .map(newConfig => new UseNamedParameters(newConfig))

  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree
      .collect {
        case Init(_, name, argss) =>
          resolveMethodSignatureFromSymbol(name.symbol) match {
            case Some(methodSig) =>
              val patchGens: List[(Term, Int) => Patch] =
                methodSig.parameterLists.zipWithIndex.map { case (_, idx) => mkPatchGenForArgList(methodSig, idx) }
              argss
                .zip(patchGens)
                .flatMap {
                  case (argsInBlock, patchGen) =>
                    if (shouldPatchArgumentBlock(argsInBlock))
                      argsInBlock.zipWithIndex.map { case (t, idx) => patchGen(t, idx) }
                    else
                      List.empty
                }
            case None => List.empty
          }
        case Term.Apply(fun, args) =>
          if (shouldPatchArgumentBlock(args)) {
            val fname = resolveFunctionTerm(fun)
            val methodSignatureOpt =
              resolveMethodSignatureFromSymbol(fname.symbol).orElse(resolveFromSynthetics(fname))
            methodSignatureOpt match {
              case Some(methodSig)
                  if methodSig.parameterLists.nonEmpty => // parameterLists.nonEmpty filters out FunctionX types
                val patchGen: (Term, Int) => Patch =
                  mkPatchGenForArgList(methodSig, determineParamBlockIndex(fname))
                args.zipWithIndex.map { case (t, idx) => patchGen(t, idx) }
              case _ => List.empty
            }
          } else
            List.empty
      }
      .flatten
      .asPatch
  }

  private def shouldPatchArgumentBlock(argTerms: List[Term]): Boolean =
    argTerms.lengthCompare(config.minParams) != -1

  private def resolveFunctionTerm(term: Term): Term =
    term match {
      case fname: Term.Name => fname
      case fname: Term.Apply =>
        // For curried functions, return the Term as is as we need
        // it to figure out which param block we're currently handling
        fname
      case Term.ApplyType(fname, _) => fname
    }

  private def mkPatchGenForArgList(
    methodSig: MethodSignature,
    paramBlockIdx: Int
  )(implicit doc: SemanticDocument): (Term, Int) => Patch = {
    val thisParamBlock = methodSig.parameterLists(paramBlockIdx)
    (term: Term, idx: Int) => {
      term match {
        case _: Term.Assign => Patch.empty // Already using named param, no patch needed
        case t =>
          // Term.Name will escape any weird identifiers
          val paramName = Term.Name(thisParamBlock(idx).displayName).toString
          Patch.addLeft(t, s"$paramName = ")
      }
    }
  }

  private def resolveMethodSignatureFromSymbol(
    funcSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[MethodSignature] =
    funcSymbol.info.map(_.signature).collect {
      case m: MethodSignature => m
    }

  // To resolve companion object .apply methods
  private def resolveFromSynthetics(funcTerm: Term)(implicit doc: SemanticDocument): Option[MethodSignature] = {
    funcTerm.synthetics
      .flatMap(_.symbol)
      .flatMap(_.info)
      .map(_.signature)
      .collectFirst {
        case m: MethodSignature => m
      }
  }

  @tailrec
  private def determineParamBlockIndex(curFuncTerm: Term, curIndex: Int = 0): Int =
    curFuncTerm match {
      case Term.Apply(innerFuncTerm, _) => determineParamBlockIndex(innerFuncTerm, curIndex + 1)
      case _ => curIndex
    }
}
