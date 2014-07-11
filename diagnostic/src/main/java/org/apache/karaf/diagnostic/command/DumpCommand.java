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
package org.apache.karaf.diagnostic.command;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.apache.karaf.diagnostic.core.DumpDestination;
import org.apache.karaf.diagnostic.core.DumpProvider;
import org.apache.karaf.diagnostic.core.common.DirectoryDumpDestination;
import org.apache.karaf.diagnostic.core.common.ZipDumpDestination;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;

/**
 * Command to create dump from shell.
 */
@Command(scope = "dev", name = "dump-create", description = "Creates zip archive with diagnostic info.")
@Service
public class DumpCommand implements Action {

    /**
     * Output format of the filename if not defined otherwise
     */
    private SimpleDateFormat dumpFormat = new SimpleDateFormat("yyyy-MM-dd_HHmmss");

    /**
     * Directory switch.
     */
    @Option(name = "-d", aliases = "--directory", description = "Creates dump in a directory in place of a ZIP archive")
    boolean directory;

    /**
     * Name of created directory or archive.
     */
    @Argument(name = "name", description = "Name of created zip or directory", required = false)
    String fileName;

    @Reference
    List<DumpProvider> providers;

    @Override
    public Object execute() throws Exception {
        DumpDestination destination;

        if (providers.isEmpty()) {
            System.out.println("Unable to create dump. No providers were found");
            return null;
        }

        // create default file name if none provided
        if (fileName == null || fileName.trim().length() == 0) {
            fileName = dumpFormat.format(new Date());
            if (!directory) {
                fileName += ".zip";
            }
        }
        File target = new File(fileName);

        // if directory switch is on, create dump in directory
        if (directory) {
            destination = new DirectoryDumpDestination(target);
        } else {
            destination = new ZipDumpDestination(target);
        }

        for (DumpProvider provider : providers) {
            provider.createDump(destination);
        }
        destination.save();
        System.out.println("Diagnostic dump created at " + target.getAbsolutePath() + ".");

        return null;
    }

}
