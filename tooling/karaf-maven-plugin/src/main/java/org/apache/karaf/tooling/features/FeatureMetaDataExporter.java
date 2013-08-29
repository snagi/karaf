/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.tooling.features;

import java.io.OutputStream;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartDocument;

import org.apache.karaf.tooling.features.model.BundleRef;
import org.apache.karaf.tooling.features.model.Feature;

/**
 * Export feature meta data as xml into a stream
 */
public class FeatureMetaDataExporter {

    private XMLEventWriter writer;
    private XMLEventFactory factory;

    public FeatureMetaDataExporter(OutputStream out) throws XMLStreamException {
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        writer = outputFactory.createXMLEventWriter(out);
        factory = XMLEventFactory.newInstance();
        StartDocument startDocument = factory.createStartDocument();
        writer.add(startDocument);
        newLine();
        writer.add(factory.createStartElement("", "", "bundles"));
        newLine();
    }

    public void writeFeature(Feature feature) throws XMLStreamException {
        writer.add(factory.createStartElement("", "", "feature"));
        writer.add(factory.createAttribute("name", feature.getName()));
        if (feature.getVersion() != null) {
            writer.add(factory.createAttribute("version", feature.getVersion()));
        }
        newLine();
        for (BundleRef bundle : feature.getBundles()) {
            writer.add(factory.createStartElement("", "", "bundle"));
            if (bundle.getStartLevel() != null) {
                writer.add(factory.createAttribute("start-level", bundle.getStartLevel().toString()));
            }
            if (bundle.getArtifact()!=null) {
                String name = MavenUtil.getFileName(bundle.getArtifact());
                writer.add(factory.createAttribute("name", name));
            }
            
            writer.add(factory.createCharacters(bundle.getUrl()));
            endElement("bundle");
        }
        endElement("feature");
    }

    public void close() throws XMLStreamException {
        endElement("bundles");
        writer.add(factory.createEndDocument());
        writer.close();
    }
    

    private void endElement(String elementName) throws XMLStreamException {
        writer.add(factory.createEndElement("", "", elementName));
        newLine();
    }

    private void newLine() throws XMLStreamException {
        writer.add(factory.createCharacters("\r\n"));
    }

}