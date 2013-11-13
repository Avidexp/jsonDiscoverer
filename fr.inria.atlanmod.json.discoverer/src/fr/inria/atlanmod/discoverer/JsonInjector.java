/*******************************************************************************
 * Copyright (c) 2008, 2013
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Javier Canovas (javier.canovas@inria.fr) 
 *******************************************************************************/

package fr.inria.atlanmod.discoverer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

/**
 * This class performs the injection process (obtaining models from JSON files)
 * This implementation does not depend on Xtext
 * 
 * @author Javier Canovas (javier.canovas@inria.fr)
 *
 */
public class JsonInjector {
	EPackage metamodel = null;
	private final static Logger LOGGER = Logger.getLogger(JsonInjector.class.getName());

	/**
	 * Injects the model conforming to a metamodel from a JSON file
	 * 
	 * @param jsonFile
	 * @param metamodelFile
	 * @return
	 */
	public List<EObject> inject(File jsonFile, File metamodelFile) throws FileNotFoundException {
		JsonElement rootElement = (new JsonParser()).parse(new JsonReader(new FileReader(jsonFile)));
		EPackage metamodel = loadMetamodel(metamodelFile);
		return inject(rootElement, metamodel);
	}

	/**
	 * Injects the model conforming to a metamodel from a JSON file
	 * 
	 * @param jsonFile
	 * @param metamodelFile
	 * @return
	 */
	public List<EObject> inject(String jsonString, EPackage ePackage) {
		JsonElement rootElement = (new JsonParser()).parse(new JsonReader(new StringReader(jsonString)));
		return inject(rootElement, ePackage);
	}
	
	/**
	 * Injects the model conforming to a metamodel from a JSON file
	 * 
	 * @param jsonFile
	 * @param metamodelFile
	 * @return
	 */
	public List<EObject> inject(File jsonFile, EPackage ePackage) throws FileNotFoundException  {
		JsonElement rootElement = (new JsonParser()).parse(new JsonReader(new FileReader(jsonFile)));
		return inject(rootElement, ePackage);
	}
	
	/**
	 * Injects a model conforming to the metamodel from a set of Json Objects
	 * 
	 * @param rootElement
	 * @param ePackage
	 * @return
	 */
	private List<EObject> inject(JsonElement rootElement, EPackage ePackage) {
		// Getting the JSON objects
		List<JsonObject> elements = new ArrayList<JsonObject>();
		if (rootElement.isJsonArray()) {
			LOGGER.finer("Several objects found");
			for(int i = 0; i < rootElement.getAsJsonArray().size(); i++)
				if(rootElement.getAsJsonArray().get(i).isJsonObject())
					elements.add(rootElement.getAsJsonArray().get(i).getAsJsonObject());
		} else if(rootElement.isJsonObject()) {
			LOGGER.finer("Only one object found");
			elements.add(rootElement.getAsJsonObject());
		} else {
			LOGGER.finest("The root element was " + rootElement.getClass().getName());
			LOGGER.finest("It is: " + rootElement.getAsString());
		}
		
		// Getting the root element
		metamodel = ePackage;
		EClassifier eClassifier = metamodel.getEClassifier("Root"); // TODO Support other root names
		
		List<EObject> eObjects = new ArrayList<EObject>();
		for(JsonObject jsonObject : elements) {
			EObject eObject = instantiateEClassifier(eClassifier, jsonObject);
			eObjects.add(eObject);
		}

		return eObjects;
	}

	protected EObject instantiateEClassifier(EClassifier eClassifier, JsonObject jsonObject) {
		EObject result = null;

		if (eClassifier instanceof EClass) {
			EClass eClass = (EClass) eClassifier;
			result = EcoreUtil.create(eClass);
			
			Iterator<Map.Entry<String, JsonElement>> pairs = jsonObject.entrySet().iterator();
			while(pairs.hasNext()) {
				Map.Entry<String, JsonElement> pair = pairs.next();
				
				String pairId = pair.getKey();
				JsonElement value = pair.getValue();

				EStructuralFeature eStructuralFeature = eClass.getEStructuralFeature(pairId);
				if(eStructuralFeature != null) {
					if(value.isJsonArray()) {
						for(int i = 0; i < value.getAsJsonArray().size(); i++) {
							JsonElement singleValue = value.getAsJsonArray().get(i);
							setStructuralFeature(result, eStructuralFeature, singleValue);
						}
					} else {
						setStructuralFeature(result, eStructuralFeature, value);
					}
				}
			}
		}

		return result;
	}

	protected void setStructuralFeature(EObject result, EStructuralFeature eStructuralFeature, JsonElement value) {
		LOGGER.finer("Setting feature " + eStructuralFeature.getName());
		if (eStructuralFeature instanceof EAttribute) {
			EAttribute eAttribute = (EAttribute) eStructuralFeature;
			if(eStructuralFeature.getUpperBound() == -1) {
				EList<Object> set = (EList<Object>) result.eGet(eAttribute);
				set.add(digestValue(value));
			} else {
				result.eSet(eAttribute, digestValue(value));
			}
		} else if(eStructuralFeature instanceof EReference) {
			EReference eReference = (EReference) eStructuralFeature;
			if(value.isJsonObject()) {
				JsonObject childJsonObject = value.getAsJsonObject();
				String childClassName = eReference.getEType().getName();
				EClassifier eChildClassifier = metamodel.getEClassifier(childClassName);
				if(eChildClassifier != null) {
					EObject child = instantiateEClassifier(eChildClassifier, childJsonObject);
					if(eStructuralFeature.getUpperBound() == -1) {
						EList<Object> set = (EList<Object>) result.eGet(eReference);
						set.add(child);
					} else {
						result.eSet(eReference, child);
					}
				}
			}
		}
	}

	protected Object digestValue(JsonElement value) {
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			return value.getAsJsonPrimitive().getAsString();
		} else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			return new Integer(value.getAsJsonPrimitive().getAsNumber().intValue());
		} else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
			return value.getAsJsonPrimitive().getAsBoolean() ? Boolean.TRUE : Boolean.FALSE;
		} else {
			return null;
		}
	}

	protected EPackage loadMetamodel(File metamodelFile) {
		ResourceSet rset = new ResourceSetImpl();
		Resource res = rset.getResource(URI.createFileURI(metamodelFile.getAbsolutePath()), true);

		try {
			res.load(null);
		} catch (IOException e) {
			e.printStackTrace();
		}

		return (EPackage) res.getContents().get(0);
	}

}
