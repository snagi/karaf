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
package org.apache.karaf.shell.ssh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.api.console.Terminal;
import org.apache.sshd.ClientChannel;
import org.apache.sshd.ClientSession;
import org.apache.sshd.SshClient;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.client.UserInteraction;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.common.util.NoCloseInputStream;
import org.apache.sshd.common.util.NoCloseOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Command(scope = "ssh", name = "ssh", description = "Connects to a remote SSH server")
@Service
public class SshAction implements Action {
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Option(name="-l", aliases={"--username"}, description = "The user name for remote login", required = false, multiValued = false)
    private String username;

    @Option(name="-P", aliases={"--password"}, description = "The password for remote login", required = false, multiValued = false)
    private String password;

    @Option(name="-p", aliases={"--port"}, description = "The port to use for SSH connection", required = false, multiValued = false)
    private int port = 22;
    
    @Option(name="-q", description = "Quiet Mode. Do not ask for confirmations", required = false, multiValued = false)
    private boolean quiet;

    @Argument(index = 0, name = "hostname", description = "The host name to connect to via SSH", required = true, multiValued = false)
    private String hostname;

    @Argument(index = 1, name = "command", description = "Optional command to execute", required = false, multiValued = true)
    private List<String> command;

    @Reference
    private Session session;

    @Reference
    private SshClientFactory sshClientFactory;

    private final static String keyChangedMessage =
            " @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ \n" +
            " @    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!      @ \n" +
            " @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ \n" +
            "IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\n" +
            "Someone could be eavesdropping on you right now (man-in-the-middle attack)!\n" +
            "It is also possible that the RSA host key has just been changed.\n" +
            "Please contact your system administrator.\n" +
            "Add correct host key in " + System.getProperty("user.home") + "/.sshkaraf/known_hosts to get rid of this message.\n" +
            "Offending key in " + System.getProperty("user.home") + "/.sshkaraf/known_hosts\n" +
            "RSA host key has changed and you have requested strict checking.\n" +
            "Host key verification failed.";

    public void setSshClientFactory(SshClientFactory sshClientFactory) {
		this.sshClientFactory = sshClientFactory;
	}

	@Override
    public Object execute() throws Exception {

        if (hostname.indexOf('@') >= 0) {
            if (username == null) {
                username = hostname.substring(0, hostname.indexOf('@'));
            }
            hostname = hostname.substring(hostname.indexOf('@') + 1, hostname.length());
        }

        System.out.println("Connecting to host " + hostname + " on port " + port);

        // If not specified, assume the current user name
        if (username == null) {
            username = (String) this.session.get("USER");
        }
        // If the username was not configured via cli, then prompt the user for the values
        if (username == null) {
            log.debug("Prompting user for login");
            if (username == null) {
                username = session.readLine("Login: ", null);
            }
        }

        SshClient client = sshClientFactory.create(quiet);
        log.debug("Created client: {}", client);
        client.start();

        String agentSocket = null;
        if (this.session.get(SshAgent.SSH_AUTHSOCKET_ENV_NAME) != null) {
            agentSocket = this.session.get(SshAgent.SSH_AUTHSOCKET_ENV_NAME).toString();
            client.getProperties().put(SshAgent.SSH_AUTHSOCKET_ENV_NAME,agentSocket);
        }
        client.setUserInteraction(new UserInteraction() {
            public void welcome(String banner) {
                System.out.println(banner);
            }
            public String[] interactive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
                String[] answers = new String[prompt.length];
                try {
                    for (int i = 0; i < prompt.length; i++) {
                        answers[i] = session.readLine(prompt[i] + " ", echo[i] ? null : '*');
                    }
                } catch (IOException e) {
                }
                return answers;
            }
        });

        try {
            ClientSession sshSession = client.connect(username, hostname, port).await().getSession();

            Object oldIgnoreInterrupts = this.session.get(Session.IGNORE_INTERRUPTS);

            try {

                if (password != null) {
                    sshSession.addPasswordIdentity(password);
                }
                sshSession.auth().verify();

                System.out.println("Connected");
                this.session.put( Session.IGNORE_INTERRUPTS, Boolean.TRUE );

                StringBuilder sb = new StringBuilder();
                if (command != null) {
                    for (String cmd : command) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(cmd);
                    }
                }

                ClientChannel channel;
                if (sb.length() > 0) {
                    channel = sshSession.createChannel("exec", sb.append("\n").toString());
                    channel.setIn(new ByteArrayInputStream(new byte[0]));
                } else {
                    channel = sshSession.createChannel("shell");
                    channel.setIn(new NoCloseInputStream(System.in));
                    ((ChannelShell) channel).setPtyColumns(getTermWidth());
                    ((ChannelShell) channel).setupSensibleDefaultPty();
                    ((ChannelShell) channel).setAgentForwarding(true);
                    Object ctype = session.get("LC_CTYPE");
                    if (ctype != null) {
                        ((ChannelShell) channel).setEnv("LC_CTYPE", ctype.toString());
                    }
                }
                channel.setOut(new NoCloseOutputStream(System.out));
                channel.setErr(new NoCloseOutputStream(System.err));
                channel.open().verify();
                channel.waitFor(ClientChannel.CLOSED, 0);
            } finally {
                session.put( Session.IGNORE_INTERRUPTS, oldIgnoreInterrupts );
                sshSession.close(false);
            }
        } finally {
            client.stop();
        }

        return null;
    }

    private int getTermWidth() {
        Terminal term = session.getTerminal();
        return term != null ? term.getWidth() : 80;
    }

}
