package org.phenoscape.owl.report

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer
import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap
import scala.collection.mutable
import scala.collection.Map
import org.apache.commons.lang3.StringUtils
import org.jdom2.filter.ElementFilter
import org.jdom2.input.SAXBuilder
import org.jdom2.Element
import org.jdom2.Namespace
import org.phenoscape.owl.util.PhenoXMLUtil.EQ
import org.phenoscape.owl.util.PhenoXMLUtil
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.OWLAnnotationProperty
import org.semanticweb.owlapi.model.OWLClassExpression
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider
import org.semanticweb.owlapi.util.DefaultPrefixManager
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary
import uk.ac.manchester.cs.owl.owlapi.mansyntaxrenderer.ManchesterOWLSyntaxOWLObjectRendererImpl
import uk.ac.manchester.cs.owl.owlapi.mansyntaxrenderer.ManchesterOWLSyntaxPrefixNameShortFormProvider
import org.semanticweb.owlapi.util.SimpleShortFormProvider
import org.semanticweb.owlapi.model.OWLObjectProperty

object AnnotationReport {

  val ONTOLOGY_PROPERTY = "org.phenoscape.owl.report.AnnotationReport.ontology";
  val OUTPUT_PROPERTY = "org.phenoscape.owl.report.AnnotationReport.output";
  val nexmlNS = Namespace.getNamespace("http://www.nexml.org/2009");
  val phenoNS = Namespace.getNamespace("http://www.bioontologies.org/obd/schema/pheno");
  val factory = OWLManager.getOWLDataFactory();
  val manager = OWLManager.createOWLOntologyManager();
  val header = "File\tCharacter Number\tCharacter Label\tState Symbol\tState Label\tEntity ID\tEntity Label\tQuality ID\tQuality Label\tRelated Entity ID\tRelated Entity Label";
  val idRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
  val prefixManager = new DefaultPrefixManager();
  prefixManager.setPrefix("UBERON:", "http://purl.obolibrary.org/obo/UBERON_");
  prefixManager.setPrefix("BSPO:", "http://purl.obolibrary.org/obo/BSPO_");
  prefixManager.setPrefix("PATO:", "http://purl.obolibrary.org/obo/PATO_");
  prefixManager.setPrefix("GO:", "http://purl.obolibrary.org/obo/GO_");
  idRenderer.setShortFormProvider(new SimpleShortFormProvider());
  val labelRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
  val label = factory.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI());
  labelRenderer.setShortFormProvider(new AnnotationValueShortFormProvider(List(label), HashMap[OWLAnnotationProperty, java.util.List[String]](), manager));

  def main(args: Array[String]): Unit = {
    manager.loadOntologyFromOntologyDocument(new File(System.getProperty(ONTOLOGY_PROPERTY)));
    val builder = new SAXBuilder();
    val properties = mutable.Set[OWLObjectProperty]();
    val writer = new BufferedWriter(new FileWriter(System.getProperty(OUTPUT_PROPERTY)));
    writer.write(header);
    writer.newLine();
    for (arg <- args) {
      val file = new File(arg);
      val nexml = builder.build(file).getRootElement();
      val pub = file.getName();
      val format = nexml.getChild("characters", nexmlNS).getChild("format", nexmlNS);
      val stateSets = format.getChildren("states", nexmlNS);
      val stateSetsByID = stateSets.map(states => (states.getAttributeValue("id"), states.getChildren("state", nexmlNS).toIterable)).toMap;
      val characters = format.getChildren("char", nexmlNS);
      var i = 0;
      for (character <- characters) {
        i += 1;
        val states = getStates(character, stateSetsByID);
        for (state <- states) {
          val phenotypes = getPhenotypes(state);
          for (phenotype <- phenotypes) {
            writer.write(pub);
            writer.write("\t");
            writeCharacter(character, i, writer);
            writer.write("\t");
            writeState(state, writer);
            writer.write("\t");
            if (phenotype.entity != null) {
              properties.addAll(phenotype.entity.getObjectPropertiesInSignature());
              writer.write(getID(phenotype.entity));
              writer.write("\t");
              writer.write(getLabel(phenotype.entity));
            } else {
              writer.write("\t");
            }
            writer.write("\t");
            if (phenotype.quality != null) {
              properties.addAll(phenotype.quality.getObjectPropertiesInSignature());
              writer.write(getID(phenotype.quality));
              writer.write("\t");
              writer.write(getLabel(phenotype.quality));
            } else {
              writer.write("\t");
            }
            writer.write("\t");
            if (phenotype.relatedEntity != null) {
              properties.addAll(phenotype.relatedEntity.getObjectPropertiesInSignature());
              writer.write(getID(phenotype.relatedEntity));
              writer.write("\t");
              writer.write(getLabel(phenotype.relatedEntity));
            } else {
              writer.write("\t");
            }
            writer.write("\t");
            writer.newLine();
          }
        }
      }
    }
    writer.close();
    for (property <- properties) {
      println(property.getIRI() + " " + labelRenderer.render(property));
    }
  }

  def getStates(character: Element, sets: Map[String, Iterable[Element]]): Iterable[Element] = {
    val statesID = character.getAttributeValue("states");
    return sets.getOrElse(statesID, List());
  }

  def getPhenotypes(state: Element): Iterable[EQ] = {
    val phenotypes = state.getDescendants(new ElementFilter("phenotype_character", phenoNS)).iterator();
    return phenotypes.map(PhenoXMLUtil.translatePhenotypeCharacter(_)).toSeq;
  }

  def getID(owlClass: OWLClassExpression): String = {
    return idRenderer.render(owlClass).replaceAll("\n", "").replaceAll("\\s+", " ");
  }

  def getLabel(owlClass: OWLClassExpression): String = {
    return labelRenderer.render(owlClass).replaceAll("\n", "").replaceAll("\\s+", " ");
  }

  def writeCharacter(character: Element, index: Int, writer: Writer): Unit = {
    writer.write(index + "");
    writer.write("\t");
    writer.write(StringUtils.defaultString(character.getAttributeValue("label")));
  }

  def writeState(state: Element, writer: Writer): Unit = {
    writer.write(StringUtils.defaultString(state.getAttributeValue("symbol")));
    writer.write("\t");
    writer.write(StringUtils.defaultString(state.getAttributeValue("label")));
  }

}