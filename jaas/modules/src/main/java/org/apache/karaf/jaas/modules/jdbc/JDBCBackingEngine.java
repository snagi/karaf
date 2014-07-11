/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.karaf.jaas.modules.jdbc;

import org.apache.karaf.jaas.boot.principal.GroupPrincipal;
import org.apache.karaf.jaas.boot.principal.RolePrincipal;
import org.apache.karaf.jaas.boot.principal.UserPrincipal;
import org.apache.karaf.jaas.modules.BackingEngine;
import org.apache.karaf.jaas.modules.encryption.EncryptionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JDBCBackingEngine implements BackingEngine {

    private final Logger logger = LoggerFactory.getLogger(JDBCBackingEngine.class);

    private DataSource dataSource;
    private EncryptionSupport encryptionSupport;

    private String addUserStatement = "INSERT INTO USERS VALUES(?,?)";
    private String addRoleStatement = "INSERT INTO ROLES VALUES(?,?)";
    private String deleteRoleStatement = "DELETE FROM ROLES WHERE USERNAME=? AND ROLE=?";
    private String deleteAllUserRolesStatement = "DELETE FROM ROLES WHERE USERNAME=?";
    private String deleteUserStatement = "DELETE FROM USERS WHERE USERNAME=?";
    private String selectUsersQuery = "SELECT USERNAME FROM USERS";
    private String selectRolesQuery = "SELECT ROLE FROM ROLES WHERE USERNAME=?";

    /**
     * Constructor
     */
    public JDBCBackingEngine(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public JDBCBackingEngine(DataSource dataSource, EncryptionSupport encryptionSupport) {
        this.dataSource = dataSource;
        this.encryptionSupport = encryptionSupport;
    }

    /**
     * Adds a new user.
     */
    public void addUser(String username, String password) {
        if (username.startsWith(GROUP_PREFIX)) {
            throw new IllegalArgumentException("Prefix not permitted: " + GROUP_PREFIX);
        }
        //If encryption support is enabled, encrypt password
        if (encryptionSupport != null && encryptionSupport.getEncryption() != null) {
            password = encryptionSupport.getEncryption().encryptPassword(password);
            if (encryptionSupport.getEncryptionPrefix() != null) {
                password = encryptionSupport.getEncryptionPrefix() + password;
            }
            if (encryptionSupport.getEncryptionSuffix() != null) {
                password = password + encryptionSupport.getEncryptionSuffix();
            }
        }
        try {
            try (Connection connection = dataSource.getConnection()) {
                rawUpdate(connection, addUserStatement, username, password);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error adding user", e);
        }
    }

    /**
     * Delete user by username.
     */
    public void deleteUser(String username) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                rawUpdate(connection, deleteAllUserRolesStatement, username);
                rawUpdate(connection, deleteUserStatement, username);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting user", e);
        }
    }

    /**
     * List all Users
     */
    public List<UserPrincipal> listUsers() {
        try {
            try (Connection connection = dataSource.getConnection()) {
                List<UserPrincipal> users = new ArrayList<>();
                for (String name : rawSelect(connection, selectUsersQuery)) {
                    if (!name.startsWith(GROUP_PREFIX)) {
                        users.add(new UserPrincipal(name));
                    }
                }
                return users;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error listing users", e);
        }
    }

    /**
     * List the roles of the {@param principal}.
     */
    public List<RolePrincipal> listRoles(Principal principal) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                if (principal instanceof GroupPrincipal) {
                    return listRoles(connection, GROUP_PREFIX + principal.getName());
                } else {
                    return listRoles(connection, principal.getName());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error listing roles", e);
        }
    }

    private List<RolePrincipal> listRoles(Connection connection, String name) throws SQLException {
        List<RolePrincipal> roles = new ArrayList<>();
        for (String role : rawSelect(connection, selectRolesQuery, name)) {
            if (role.startsWith(GROUP_PREFIX)) {
                roles.addAll(listRoles(connection, role));
            } else {
                roles.add(new RolePrincipal(role));
            }
        }
        return roles;
    }

    /**
     * Add a role to a user.
     */
    public void addRole(String username, String role) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                rawUpdate(connection, addRoleStatement, username, role);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error adding role", e);
        }
    }

    /**
     * Remove role from user.
     */
    public void deleteRole(String username, String role) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                rawUpdate(connection, deleteRoleStatement, username, role);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting role", e);
        }
    }

    @Override
    public List<GroupPrincipal> listGroups(UserPrincipal principal) {
        try {
            try (Connection connection = dataSource.getConnection()) {
            List<GroupPrincipal> roles = new ArrayList<>();
            for (String role : rawSelect(connection, selectRolesQuery, principal.getName())) {
                if (role.startsWith(GROUP_PREFIX)) {
                    roles.add(new GroupPrincipal(role.substring(GROUP_PREFIX.length())));
                }
            }
            return roles;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting role", e);
        }
    }

    @Override
    public void addGroup(String username, String group) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                String groupName = GROUP_PREFIX + group;
                rawUpdate(connection, addRoleStatement, username, groupName);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            logger.error("Error executing statement", e);
        }
    }

    @Override
    public void deleteGroup(String username, String group) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                rawUpdate(connection, deleteRoleStatement, username, GROUP_PREFIX + group);
                // garbage collection, clean up the groups if needed
                boolean inUse = false;
                for (String user : rawSelect(connection, selectUsersQuery)) {
                    for (String g : rawSelect(connection, selectRolesQuery, user)) {
                        if (group.equals(g)) {
                            // there is another user of this group, nothing to clean up
                            inUse = true;
                            break;
                        }
                    }
                }
                // nobody is using this group any more, remove it
                if (!inUse) {
                    rawUpdate(connection, deleteAllUserRolesStatement, GROUP_PREFIX + group);
                }
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            logger.error("Error executing statement", e);
        }
    }

    @Override
    public void addGroupRole(String group, String role) {
        addRole(GROUP_PREFIX + group, role);
    }

    @Override
    public void deleteGroupRole(String group, String role) {
        deleteRole(GROUP_PREFIX + group, role);
    }

    protected void rawUpdate(Connection connection, String query, String... params) throws SQLException {
        int rows = JDBCUtils.rawUpdate(connection, query, params);
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Executing [%s], params=%s. %d rows affected.", query, Arrays.toString(params), rows));
        }
    }

    protected List<String> rawSelect(Connection connection, String query, String... params) throws SQLException {
        return JDBCUtils.rawSelect(connection, query, params);
    }

    public String getAddUserStatement() {
        return addUserStatement;
    }

    public void setAddUserStatement(String addUserStatement) {
        this.addUserStatement = addUserStatement;
    }

    public String getAddRoleStatement() {
        return addRoleStatement;
    }

    public void setAddRoleStatement(String addRoleStatement) {
        this.addRoleStatement = addRoleStatement;
    }

    public String getDeleteRoleStatement() {
        return deleteRoleStatement;
    }

    public void setDeleteRoleStatement(String deleteRoleStatement) {
        this.deleteRoleStatement = deleteRoleStatement;
    }

    public String getDeleteAllUserRolesStatement() {
        return deleteAllUserRolesStatement;
    }

    public void setDeleteAllUserRolesStatement(String deleteAllUserRolesStatement) {
        this.deleteAllUserRolesStatement = deleteAllUserRolesStatement;
    }

    public String getDeleteUserStatement() {
        return deleteUserStatement;
    }

    public void setDeleteUserStatement(String deleteUserStatement) {
        this.deleteUserStatement = deleteUserStatement;
    }

    public String getSelectUsersQuery() {
        return selectUsersQuery;
    }

    public void setSelectUsersQuery(String selectUsersQuery) {
        this.selectUsersQuery = selectUsersQuery;
    }

    public String getSelectRolesQuery() {
        return selectRolesQuery;
    }

    public void setSelectRolesQuery(String selectRolesQuery) {
        this.selectRolesQuery = selectRolesQuery;
    }

}
