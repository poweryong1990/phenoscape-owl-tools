package org.phenoscape.owl.mod.xenbase

import java.io.File
import scala.collection.JavaConversions._
import scala.collection.Map
import scala.collection.Set
import scala.collection.TraversableOnce.flattenTraversableOnce
import scala.collection.mutable
import scala.io.Source
import org.apache.commons.lang3.StringUtils
import org.phenoscape.scowl.OWL._
import org.phenoscape.owl.OWLTask
import org.phenoscape.owl.Vocab
import org.phenoscape.owl.Vocab._
import org.phenoscape.owl.util.OBOUtil
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.OWLAxiom
import org.semanticweb.owlapi.model.OWLNamedIndividual
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.apibinding.OWLManager
import org.phenoscape.owl.util.OntologyUtil

object XenbaseExpressionToOWL extends OWLTask {

  val laevis = Individual(Vocab.XENOPUS_LAEVIS)
  val tropicalis = Individual(Vocab.XENOPUS_TROPICALIS)
  val manager = OWLManager.createOWLOntologyManager()

  def main(args: Array[String]): Unit = {
    val genepageMappingsFile = Source.fromFile(args(0), "utf-8")
    val laevisExpressionFile = Source.fromFile(args(1), "utf-8")
    val tropicalisExpressionFile = Source.fromFile(args(2))
    val ontology = convert(genepageMappingsFile, laevisExpressionFile, tropicalisExpressionFile)
    genepageMappingsFile.close()
    laevisExpressionFile.close()
    tropicalisExpressionFile.close()
    manager.saveOntology(ontology, IRI.create(new File(args(3))))
  }

  def convert(genepageMappingsFile: Source, laevisExpressionFile: Source, tropicalisExpressionFile: Source): OWLOntology = {
    val mappings = indexGenepageMappings(genepageMappingsFile)
    val ontology = convert(laevisExpressionFile, mappings, laevis)
    val tropicalisOntology = convert(tropicalisExpressionFile, mappings, tropicalis)
    manager.addAxioms(ontology, tropicalisOntology.getAxioms())
    return ontology
  }

  def indexGenepageMappings(mappings: Source): Map[String, String] = {
    val index = mutable.Map[String, String]()
    for (mapping <- mappings.getLines()) {
      val items = mapping.split("\t")
      val genepageID = StringUtils.stripToNull(items(0))
      for (geneID <- items(1).split(",")) {
        index(StringUtils.stripToNull(geneID)) = genepageID
      }
    }
    return index
  }

  def convert(expressionData: Source, genepageMappings: Map[String, String], species: OWLNamedIndividual): OWLOntology = {
    val id = if (species == laevis) "http://purl.obolibrary.org/obo/phenoscape/xenbase_gene_expression.owl" else ""
    val ontology = manager.createOntology(IRI.create(id))
    manager.addAxioms(ontology, expressionData.getLines.map(translate(_, genepageMappings, species)).flatten.toSet[OWLAxiom])
    val rdfsLabel = factory.getRDFSLabel()
    manager.addAxiom(ontology, laevis Annotation (rdfsLabel, factory.getOWLLiteral("Xenopus laevis")))
    manager.addAxiom(ontology, tropicalis Annotation (rdfsLabel, factory.getOWLLiteral("Xenopus tropicalis")))
    return ontology
  }

  def translate(expressionLine: String, genepageMappings: Map[String, String], species: OWLNamedIndividual): Set[OWLAxiom] = {
    val items = expressionLine.split("\t")
    val axioms = mutable.Set[OWLAxiom]()
    if (StringUtils.stripToEmpty(items(3)) == "unspecified") {
      return axioms
    } else {
      val expression = OntologyUtil.nextIndividual()
      axioms.add(factory.getOWLDeclarationAxiom(expression))
      axioms.add(expression Type GeneExpression)
      val structureItems = items(3).split(",", -1)
      for (structureItem <- structureItems) {
        val structureID = StringUtils.stripToNull(structureItem.trim().split(" ")(0))
        val structureType = Class(OBOUtil.iriForTermID(structureID))
        val structure = OntologyUtil.nextIndividual()
        axioms.add(factory.getOWLDeclarationAxiom(structure))
        axioms.add(structure Type structureType)
        axioms.add(expression Fact (occurs_in, structure))
      }
      val evidenceText = StringUtils.stripToEmpty(items(7))
      if (evidenceText.contains("XB-IMG")) {
        val image = Individual(OBOUtil.xenbaseImageIRI(evidenceText))
        axioms.add(expression Fact (dcSource, image))
      }
      val genepageID = genepageMappings(StringUtils.stripToNull(items(0)))
      val geneIRI = XenbaseGenesToOWL.getGeneIRI(genepageID)
      val gene = Individual(geneIRI)
      axioms.add(factory.getOWLDeclarationAxiom(gene))
      axioms.add(expression Fact (associated_with_gene, gene))
      axioms.add(expression Fact (associated_with_taxon, species))
      return axioms
    }
  }

}