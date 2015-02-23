/*
 * Copyright (C) 2013      Denis Forveille titou10.titou10@gmail.com
 * Copyright (C) 2010-2015 Serge Rieder serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.ext.db2.model.app;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.db2.DB2Utils;
import org.jkiss.dbeaver.ext.db2.model.DB2DataSource;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionManager;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

/**
 * DB2 Application Manager
 * 
 * @author Denis Forveille
 */
public class DB2ServerApplicationManager implements DBAServerSessionManager<DB2ServerApplication> {

    private static final String FORCE_APP_CMD = "FORCE APPLICATION (%d)";

    private final DB2DataSource dataSource;

    public DB2ServerApplicationManager(DB2DataSource dataSource)
    {
        this.dataSource = dataSource;
    }

    @Override
    public DBPDataSource getDataSource()
    {
        return dataSource;
    }

    @Override
    public Collection<DB2ServerApplication> getSessions(DBCSession session, Map<String, Object> options) throws DBException
    {
        try {
            return DB2Utils.readApplications(session.getProgressMonitor(), (JDBCSession) session);
        } catch (SQLException e) {
            throw new DBException(e, session.getDataSource());
        }
    }

    @Override
    public void alterSession(DBCSession session, DB2ServerApplication sessionType, Map<String, Object> options) throws DBException
    {
        try {
            String cmd = String.format(FORCE_APP_CMD, sessionType.getAgentId());
            DB2Utils.callAdminCmd(session.getProgressMonitor(), dataSource, cmd);
        } catch (SQLException e) {
            throw new DBException(e, session.getDataSource());
        }
    }

}
