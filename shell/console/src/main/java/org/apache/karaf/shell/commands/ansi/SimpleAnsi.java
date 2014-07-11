/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.commands.ansi;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;

@Deprecated
public class SimpleAnsi {
    public static String COLOR_RED = Ansi.ansi().fg(Color.RED).toString();
    public static String COLOR_DEFAULT = Ansi.ansi().fg(Color.DEFAULT).toString();
    
    public static String INTENSITY_BOLD = Ansi.ansi().bold().toString();
    public static String INTENSITY_NORMAL = Ansi.ansi().boldOff().toString();
}
