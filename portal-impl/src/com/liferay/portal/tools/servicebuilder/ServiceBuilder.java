/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.tools.servicebuilder;

import com.liferay.portal.kernel.io.unsync.UnsyncBufferedReader;
import com.liferay.portal.kernel.io.unsync.UnsyncStringReader;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.tools.sourceformatter.JavaImportsFormatter;
import com.liferay.portal.xml.SAXReaderFactory;

import com.thoughtworks.qdox.model.AbstractBaseJavaEntity;
import com.thoughtworks.qdox.model.Annotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.Type;

import de.hunsicker.io.FileFormat;
import de.hunsicker.jalopy.Jalopy;
import de.hunsicker.jalopy.storage.Convention;
import de.hunsicker.jalopy.storage.ConventionKeys;
import de.hunsicker.jalopy.storage.Environment;

import java.io.File;
import java.io.IOException;

import java.net.URL;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

/**
 * @author Brian Wing Shun Chan
 * @author Charles May
 * @author Alexander Chow
 * @author Harry Mark
 * @author Tariq Dweik
 * @author Glenn Powell
 * @author Raymond Augé
 * @author Prashant Dighe
 * @author Shuyang Zhou
 * @author James Lefeu
 * @author Miguel Pastor
 * @author Cody Hoag
 * @author James Hinkey
 * @author Hugo Huijser
 */
public class ServiceBuilder {

	public static final String AUTHOR = "Brian Wing Shun Chan";

	public static String getContent(String fileName) throws Exception {
		Document document = _getContentDocument(fileName);

		Element rootElement = document.getRootElement();

		Element authorElement = null;
		Element namespaceElement = null;
		Map<String, Element> entityElements = new TreeMap<>();
		Map<String, Element> exceptionElements = new TreeMap<>();

		List<Element> elements = rootElement.elements();

		for (Element element : elements) {
			String elementName = element.getName();

			if (elementName.equals("author")) {
				element.detach();

				if (authorElement != null) {
					throw new IllegalArgumentException(
						"There can only be one author element");
				}

				authorElement = element;
			}
			else if (elementName.equals("namespace")) {
				element.detach();

				if (namespaceElement != null) {
					throw new IllegalArgumentException(
						"There can only be one namespace element");
				}

				namespaceElement = element;
			}
			else if (elementName.equals("entity")) {
				element.detach();

				String name = element.attributeValue("name");

				entityElements.put(StringUtil.toLowerCase(name), element);
			}
			else if (elementName.equals("exceptions")) {
				element.detach();

				List<Element> exceptionElementsList = element.elements(
					"exception");

				for (Element exceptionElement : exceptionElementsList) {
					exceptionElement.detach();

					exceptionElements.put(
						exceptionElement.getText(), exceptionElement);
				}
			}
		}

		if (authorElement != null) {
			rootElement.add(authorElement);
		}

		if (namespaceElement == null) {
			throw new IllegalArgumentException(
				"The namespace element is required");
		}
		else {
			rootElement.add(namespaceElement);
		}

		_addElements(rootElement, entityElements);

		if (!exceptionElements.isEmpty()) {
			Element exceptionsElement = rootElement.addElement("exceptions");

			_addElements(exceptionsElement, exceptionElements);
		}

		return document.asXML();
	}

	public static boolean hasAnnotation(
		AbstractBaseJavaEntity abstractBaseJavaEntity, String annotationName) {

		Annotation[] annotations = abstractBaseJavaEntity.getAnnotations();

		if (annotations == null) {
			return false;
		}

		for (int i = 0; i < annotations.length; i++) {
			Type type = annotations[i].getType();

			JavaClass javaClass = type.getJavaClass();

			if (annotationName.equals(javaClass.getName())) {
				return true;
			}
		}

		return false;
	}

	public static void writeFile(File file, String content) throws IOException {
		writeFile(file, content, AUTHOR);
	}

	public static void writeFile(File file, String content, String author)
		throws IOException {

		writeFile(file, content, author, null);
	}

	public static void writeFile(
			File file, String content, String author,
			Map<String, Object> jalopySettings)
		throws IOException {

		String packagePath = _getPackagePath(file);

		String className = file.getName();

		className = className.substring(0, className.length() - 5);

		content = JavaImportsFormatter.stripJavaImports(
			content, packagePath, className);

		content = _stripFullyQualifiedClassNames(content);

		File tempFile = new File("ServiceBuilder.temp");

		FileUtils.write(tempFile, content);

		// Beautify

		StringBuffer sb = new StringBuffer();

		Jalopy jalopy = new Jalopy();

		jalopy.setFileFormat(FileFormat.UNIX);
		jalopy.setInput(tempFile);
		jalopy.setOutput(sb);

		File jalopyXmlFile = new File("tools/jalopy.xml");

		if (!jalopyXmlFile.exists()) {
			jalopyXmlFile = new File("../tools/jalopy.xml");
		}

		if (!jalopyXmlFile.exists()) {
			jalopyXmlFile = new File("misc/jalopy.xml");
		}

		if (!jalopyXmlFile.exists()) {
			jalopyXmlFile = new File("../misc/jalopy.xml");
		}

		if (!jalopyXmlFile.exists()) {
			jalopyXmlFile = new File("../../misc/jalopy.xml");
		}

		if (jalopyXmlFile.exists()) {
			Jalopy.setConvention(jalopyXmlFile);
		}
		else {
			URL url = _readJalopyXmlFromClassLoader();

			Jalopy.setConvention(url);
		}

		if (jalopySettings == null) {
			jalopySettings = new HashMap<>();
		}

		Environment env = Environment.getInstance();

		// Author

		author = GetterUtil.getString(
			(String)jalopySettings.get("author"), author);

		env.set("author", author);

		// File name

		env.set("fileName", file.getName());

		Convention convention = Convention.getInstance();

		String classMask = "/**\n * @author $author$\n*/";

		convention.put(
			ConventionKeys.COMMENT_JAVADOC_TEMPLATE_CLASS,
			env.interpolate(classMask));

		convention.put(
			ConventionKeys.COMMENT_JAVADOC_TEMPLATE_INTERFACE,
			env.interpolate(classMask));

		jalopy.format();

		String newContent = sb.toString();

		// Remove double blank lines after the package or last import

		newContent = newContent.replaceFirst(
			"(?m)^[ \t]*((?:package|import) .*;)\\s*^[ \t]*/\\*\\*",
			"$1\n\n/**");

		/*
		// Remove blank lines after try {

		newContent = StringUtil.replace(newContent, "try {\n\n", "try {\n");

		// Remove blank lines after ) {

		newContent = StringUtil.replace(newContent, ") {\n\n", ") {\n");

		// Remove blank lines empty braces { }

		newContent = StringUtil.replace(newContent, "\n\n\t}", "\n\t}");

		// Add space to last }

		newContent = newContent.substring(0, newContent.length() - 2) + "\n\n}";
		*/

		writeFileRaw(file, newContent);

		tempFile.deleteOnExit();
	}

	public static void writeFileRaw(File file, String content)
		throws IOException {

		// Write file if and only if the file has changed

		if (!file.exists() ||
			!content.equals(FileUtils.readFileToString(file))) {

			FileUtils.write(file, content);

			System.out.println("Writing " + file);
		}
	}

	private static void _addElements(
		Element element, Map<String, Element> elements) {

		for (Map.Entry<String, Element> entry : elements.entrySet()) {
			Element childElement = entry.getValue();

			element.add(childElement);
		}
	}

	private static Document _getContentDocument(String fileName)
		throws Exception {

		SAXReader saxReader = _getSAXReader();

		Document document = saxReader.read(new File(fileName));

		Element rootElement = document.getRootElement();

		List<Element> elements = rootElement.elements();

		for (Element element : elements) {
			String elementName = element.getName();

			if (!elementName.equals("service-builder-import")) {
				continue;
			}

			element.detach();

			String dirName = fileName.substring(
				0, fileName.lastIndexOf(StringPool.SLASH) + 1);
			String serviceBuilderImportFileName = element.attributeValue(
				"file");

			Document serviceBuilderImportDocument = _getContentDocument(
				dirName + serviceBuilderImportFileName);

			Element serviceBuilderImportRootElement =
				serviceBuilderImportDocument.getRootElement();

			List<Element> serviceBuilderImportElements =
				serviceBuilderImportRootElement.elements();

			for (Element serviceBuilderImportElement :
					serviceBuilderImportElements) {

				serviceBuilderImportElement.detach();

				rootElement.add(serviceBuilderImportElement);
			}
		}

		return document;
	}

	private static String _getPackagePath(File file) {
		String fileName = StringUtil.replace(file.toString(), "\\", "/");

		int x = fileName.indexOf("src/");

		if (x == -1) {
			x = fileName.indexOf("test/");
		}

		int y = fileName.lastIndexOf("/");

		fileName = fileName.substring(x + 4, y);

		return StringUtil.replace(fileName, "/", ".");
	}

	private static SAXReader _getSAXReader() {
		return SAXReaderFactory.getSAXReader(null, false, false);
	}

	private static URL _readJalopyXmlFromClassLoader() {
		ClassLoader classLoader = ServiceBuilder.class.getClassLoader();

		URL url = classLoader.getResource("jalopy.xml");

		if (url == null) {
			throw new RuntimeException(
				"Unable to load jalopy.xml from the class loader");
		}

		return url;
	}

	private static String _stripFullyQualifiedClassNames(String content)
		throws IOException {

		String imports = JavaImportsFormatter.getImports(content);

		if (Validator.isNull(imports)) {
			return content;
		}

		UnsyncBufferedReader unsyncBufferedReader = new UnsyncBufferedReader(
			new UnsyncStringReader(imports));

		String line = null;

		while ((line = unsyncBufferedReader.readLine()) != null) {
			int x = line.indexOf("import ");

			if (x == -1) {
				continue;
			}

			String importPackageAndClassName = line.substring(
				x + 7, line.lastIndexOf(StringPool.SEMICOLON));

			for (x = -1;;) {
				x = content.indexOf(importPackageAndClassName, x + 1);

				if (x == -1) {
					break;
				}

				char nextChar = content.charAt(
					x + importPackageAndClassName.length());
				char previousChar = content.charAt(x - 1);

				if (Character.isAlphabetic(nextChar) ||
					(nextChar == CharPool.QUOTE) ||
					(nextChar == CharPool.SEMICOLON) ||
					(previousChar == CharPool.QUOTE)) {

					continue;
				}

				String importClassName = importPackageAndClassName.substring(
					importPackageAndClassName.lastIndexOf(StringPool.PERIOD) +
						1);

				content = StringUtil.replaceFirst(
					content, importPackageAndClassName, importClassName, x);
			}
		}

		return content;
	}

}