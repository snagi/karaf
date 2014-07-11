/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.bundle.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.Buffer;
import java.util.List;

import org.apache.karaf.bundle.command.wikidoc.AnsiPrintingWikiVisitor;
import org.apache.karaf.bundle.command.wikidoc.WikiParser;
import org.apache.karaf.bundle.command.wikidoc.WikiVisitor;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.ShellUtil;
import org.osgi.framework.Bundle;

@Command(scope = "bundle", name = "info", description = "Displays detailed information of a given bundles.")
@Service
public class Info extends BundlesCommand {

    /**
     * <p>
     * Get the OSGI-INF/bundle.info entry from the bundle and display it.
     * </p>
     *
     * @param bundle the bundle.
     */
    @Override
    protected void executeOnBundle(Bundle bundle) throws Exception {
        String title = ShellUtil.getBundleName(bundle);
        System.out.println("\n" + title);
        System.out.println(ShellUtil.getUnderlineString(title));
        URL bundleInfo = bundle.getEntry("OSGI-INF/bundle.info");
        if (bundleInfo != null) {
            try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(bundleInfo.openStream()));
            ) {
                WikiVisitor visitor = new AnsiPrintingWikiVisitor(System.out);
                WikiParser parser = new WikiParser(visitor);
                parser.parse(reader);
            } catch (Exception e) {
                // ignore
            }
        }
    }

}
