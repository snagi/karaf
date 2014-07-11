/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.impl.console;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.apache.karaf.shell.api.console.Signal;
import org.apache.karaf.shell.api.console.Terminal;
import org.apache.karaf.shell.support.terminal.SignalSupport;

/**
 * Created by gnodet on 27/02/14.
 */
public class JLineTerminal extends SignalSupport implements Terminal, Closeable {

    private final jline.Terminal terminal;

    public JLineTerminal(jline.Terminal terminal) {
        this.terminal = terminal;
        registerSignalHandler();
    }

    public jline.Terminal getTerminal() {
        return terminal;
    }

    @Override
    public int getWidth() {
        return terminal.getWidth();
    }

    @Override
    public int getHeight() {
        return terminal.getHeight();
    }

    @Override
    public boolean isAnsiSupported() {
        return terminal.isAnsiSupported();
    }

    @Override
    public boolean isEchoEnabled() {
        return terminal.isEchoEnabled();
    }

    @Override
    public void setEchoEnabled(boolean enabled) {
        terminal.setEchoEnabled(enabled);
    }

    @Override
    public void close() throws IOException {
        unregisterSignalHandler();
    }

    private void registerSignalHandler() {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");
            // Implement signal handler
            Object signalHandler = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{signalHandlerClass}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            JLineTerminal.this.signal(Signal.WINCH);
                            return null;
                        }
                    }
            );
            // Register the signal handler, this code is equivalent to:
            // Signal.handle(new Signal("CONT"), signalHandler);
            signalClass.getMethod("handle", signalClass, signalHandlerClass).invoke(null, signalClass.getConstructor(String.class).newInstance("WINCH"), signalHandler);
        } catch (Exception e) {
            // Ignore this exception, if the above failed, the signal API is incompatible with what we're expecting

        }
    }

    private void unregisterSignalHandler() {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");

            Object signalHandler = signalHandlerClass.getField("SIG_DFL").get(null);
            // Register the signal handler, this code is equivalent to:
            // Signal.handle(new Signal("CONT"), signalHandler);
            signalClass.getMethod("handle", signalClass, signalHandlerClass).invoke(null, signalClass.getConstructor(String.class).newInstance("WINCH"), signalHandler);
        } catch (Exception e) {
            // Ignore this exception, if the above failed, the signal API is incompatible with what we're expecting

        }
    }
}
