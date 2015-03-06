package org.phenoscape.owl.sim

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.semanticweb.elk.owlapi.ElkReasonerFactory
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.OWLClass
import org.semanticweb.owlapi.model.OWLNamedIndividual
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.reasoner.{Node => ReasonerNode}
import org.semanticweb.owlapi.reasoner.OWLReasoner

class OWLsim(ontology: OWLOntology, inCorpus: OWLNamedIndividual => Boolean) {

  type SuperClassOfIndex = Map[Node, Set[Node]]
  type SubClassOfIndex = Map[Node, Set[Node]]

  private val OWLThing = OWLManager.getOWLDataFactory.getOWLThing
  private val OWLNothing = OWLManager.getOWLDataFactory.getOWLNothing

  val (superClassOfIndex, subClassOfIndex, directAssociationsByNode, directAssociationsByIndividual) = {
    val reasoner = new ElkReasonerFactory().createReasoner(ontology)
    val (superClassOf, subClassOf) = nonRedundantHierarchy(reasoner)
    val (directAssocByNode, directAssocByIndividual) = indexDirectAssociations(reasoner)
    reasoner.dispose()
    (superClassOf, subClassOf, directAssocByNode, directAssocByIndividual)
  }

  val allNodes: Set[Node] = superClassOfIndex.keySet

  private val (nodeToInt, intToNode) = {
    val (nodesToInts, intsToNodes) = allNodes.zipWithIndex.map { case (node, index) => (node -> index, index -> node) }.unzip
    (nodesToInts.toMap, intsToNodes.toMap)
  }

  val classToNode: Map[OWLClass, Node] = (for {
    node <- allNodes
    aClass <- node.classes
  } yield aClass -> node).toMap

  val childToReflexiveAncestorIndex: Map[Node, Set[Node]] = indexAncestorsReflexive(classToNode(OWLNothing))

  val allIndividuals: Set[OWLNamedIndividual] = directAssociationsByIndividual.keySet

  val individualsInCorpus: Set[OWLNamedIndividual] = directAssociationsByIndividual.keySet.filter(inCorpus)

  val directAndIndirectAssociationsByIndividual = directAssociationsByIndividual.map {
    case (individual, nodes) => individual -> (nodes ++ nodes.flatMap(childToReflexiveAncestorIndex))
  }

  val corpusSize: Int = allIndividuals.size

  val directAndIndirectAssociationsByNode: Map[Node, Set[OWLNamedIndividual]] = accumulateAssociations(classToNode(OWLThing))

  val nodeIC: Map[Node, Double] = convertFrequenciesToInformationContent(classToNode(OWLNothing))

  def computeAllSimilarityToCorpus(inputs: Set[OWLNamedIndividual]): Set[SimpleSimilarity] = {
    val parallelInputs = inputs.par
    val parallelIndividualsSize = parallelInputs.size
    (for {
      inputProfile <- parallelInputs
      corpusProfile <- individualsInCorpus
    } yield {
      SimpleSimilarity(inputProfile, corpusProfile, groupWiseSimilarity(inputProfile, corpusProfile).score)
    }).seq
  }

  def nonRedundantHierarchy(reasoner: OWLReasoner): (SuperClassOfIndex, SubClassOfIndex) = {
    val parentToChildren = mutable.Map[Node, Set[Node]]()
    val childToParents = mutable.Map[Node, Set[Node]]()
    def traverse(reasonerNode: ReasonerNode[OWLClass]): Unit = {
      val parent = Node(reasonerNode)
      if (!parentToChildren.contains(parent)) {
        val representative = reasonerNode.getRepresentativeElement
        val children = reasoner.getSubClasses(representative, true).getNodes.asScala.toSet
        children.foreach { childNode =>
          traverse(childNode)
          val child = Node(childNode)
          val parents = childToParents.getOrElse(child, Set.empty)
          childToParents += (child -> (parents + parent))
        }
        parentToChildren += (parent -> children.map(Node(_)))
      }
    }
    val top = reasoner.getTopClassNode
    traverse(top)
    childToParents += (Node(top) -> Set.empty)
    (parentToChildren.toMap, childToParents.toMap)
  }

  def indexDirectAssociations(reasoner: OWLReasoner): (Map[Node, Set[OWLNamedIndividual]], Map[OWLNamedIndividual, Set[Node]]) = {
    val individuals = reasoner.getRootOntology.getIndividualsInSignature(true).asScala.toSet
    val init = (Map.empty[Node, Set[OWLNamedIndividual]], Map.empty[OWLNamedIndividual, Set[Node]])
    val individualsToNodes = (individuals.map { individual =>
      val nodes = reasoner.getTypes(individual, true).getNodes.asScala.map(Node(_)).toSet
      individual -> nodes
    }).toMap
    (invertMapOfSets(individualsToNodes), individualsToNodes)
  }

  private def accumulateAssociations(node: Node): Map[Node, Set[OWLNamedIndividual]] = {
    val index = mutable.Map[Node, Set[OWLNamedIndividual]]()
    def traverse(node: Node): Unit = {
      if (!index.contains(node)) {
        val children = superClassOfIndex(node)
        children.foreach(traverse)
        val nodeAssociations = directAssociationsByNode.getOrElse(node, Set.empty) ++ children.flatMap(index)
        index += (node -> nodeAssociations)
      }
    }
    traverse(node)
    index.toMap
  }

  private def indexAncestorsReflexive(bottom: Node): Map[Node, Set[Node]] = {
    val index = mutable.Map[Node, Set[Node]]()
    def traverse(node: Node): Unit = {
      if (!index.contains(node)) {
        val parents = subClassOfIndex(node)
        parents.foreach(traverse)
        val ancestors = parents ++ parents.flatMap(index)
        index += (node -> (ancestors + node))
      }
    }
    traverse(bottom)
    index.toMap
  }

  private def convertFrequenciesToInformationContent(bottom: Node): Map[Node, Double] = {
    val ics = mutable.Map[Node, Double]()
    def traverse(node: Node): Unit = {
      if (!ics.contains(node)) {
        val parents = subClassOfIndex(node)
        parents.foreach(traverse)
        val instancesInCorpus = directAndIndirectAssociationsByNode(node).intersect(individualsInCorpus)
        val freq = instancesInCorpus.size
        val ic = if (freq == 0) {
          parents.map(ics).max
        } else {
          -Math.log((freq.toDouble / corpusSize)) / Math.log(2)
        }
        ics += (node -> ic)
      }
    }
    traverse(bottom)
    ics.toMap
  }

  def commonSubsumersOf(i: Node, j: Node): Set[Node] = childToReflexiveAncestorIndex(i).intersect(childToReflexiveAncestorIndex(j))

  def commonSubsumersOf(i: OWLNamedIndividual, j: OWLNamedIndividual): Set[Node] =
    directAndIndirectAssociationsByIndividual(i).intersect(directAndIndirectAssociationsByIndividual(j))

  def maxICSubsumer(i: Node, j: Node): Node = if (i == j) i else commonSubsumersOf(i, j).maxBy(nodeIC)

  def groupWiseSimilarity(i: OWLNamedIndividual, j: OWLNamedIndividual): GroupWiseSimilarity = {
    val pairScores = for {
      iNode <- directAssociationsByIndividual(i)
      jNode <- directAssociationsByIndividual(j)
    } yield {
      val maxSubsumer = maxICSubsumer(iNode, jNode)
      PairScore(Map(i -> iNode, j -> jNode), maxSubsumer, nodeIC(maxSubsumer))
    }
    val medianScore = median(pairScores.map(_.maxSubsumerIC).toSeq)
    GroupWiseSimilarity(medianScore, pairScores)
  }

  def median(values: Seq[Double]): Double = {
    val (lower, upper) = values.sorted.splitAt(values.size / 2)
    if (values.size % 2 == 0) ((lower.last + upper.head) / 2.0) else upper.head
  }

  private def invertMapOfSets[K, V](in: Map[K, Set[V]]): Map[V, Set[K]] = {
    in.toIterable.flatMap {
      case (k, vs) => vs.map(_ -> k)
    }.groupBy {
      case (v, k) => v
    }.map {
      case (v, vks) => v -> vks.map {
        case (v1, k1) => k1
      }.toSet
    }
  }

}

case class Node(classes: Set[OWLClass])

object Node {

  def apply(reasonerNode: ReasonerNode[OWLClass]): Node = Node(reasonerNode.getEntities.asScala.toSet)

}

case class PairScore(annotations: Map[OWLNamedIndividual, Node], maxSubsumer: Node, maxSubsumerIC: Double)

case class GroupWiseSimilarity(score: Double, pairs: Set[PairScore])

case class SimpleSimilarity(i: OWLNamedIndividual, j: OWLNamedIndividual, score: Double) {

  override def toString() = s"${i.getIRI.toString}\t${j.getIRI.toString}\t${score}"

}
