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
package org.apache.karaf.features.command;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.command.completers.AvailableFeatureCompleter;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;

@Command(scope = "feature", name = "install", description = "Installs a feature with the specified name and version.")
@Service
public class InstallFeatureCommand extends FeaturesCommandSupport {

    private static String DEFAULT_VERSION = "0.0.0";

    @Argument(index = 0, name = "features", description = "The name and version of the features to install. A feature id looks like name/version. The version is optional.", required = true, multiValued = true)
    @Completion(AvailableFeatureCompleter.class)
    List<String> features;

    @Option(name = "-r", aliases = "--no-auto-refresh", description = "Do not automatically refresh bundles", required = false, multiValued = false)
    boolean noRefresh;

    @Option(name = "-s", aliases = "--no-auto-start", description = "Do not start the bundles", required = false, multiValued = false)
    boolean noStart;

    @Option(name = "-m", aliases = "--no-auto-manage", description = "Do not automatically manage bundles", required = false, multiValued = false)
    boolean noManage;

    @Option(name = "-v", aliases = "--verbose", description = "Explain what is being done", required = false, multiValued = false)
    boolean verbose;

    @Option(name = "-t", aliases = "--simulate", description = "Perform a simulation only", required = false, multiValued = false)
    boolean simulate;

    @Option(name = "-g", aliases = "--region", description = "Region to install to")
    String region;

    protected void doExecute(FeaturesService admin) throws Exception {
        EnumSet<FeaturesService.Option> options = EnumSet.noneOf(FeaturesService.Option.class);
        if (simulate) {
            options.add(FeaturesService.Option.Simulate);
        }
        if (noStart) {
            options.add(FeaturesService.Option.NoAutoStartBundles);
        }
        if (noRefresh) {
            options.add(FeaturesService.Option.NoAutoRefreshBundles);
        }
        if (noManage) {
            options.add(FeaturesService.Option.NoAutoManageBundles);
        }
        if (verbose) {
            options.add(FeaturesService.Option.Verbose);
        }
        admin.installFeatures(new HashSet<String>(features), region, options);
    }
}
