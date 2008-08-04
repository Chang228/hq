/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004-2008], Hyperic, Inc.
 * This file is part of HQ.
 * 
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.appdef.shared;

import java.util.List;
import java.rmi.RemoteException;

import javax.ejb.CreateException;
import javax.ejb.EJBException;
import javax.ejb.FinderException;
import javax.naming.NamingException;

import org.hyperic.hq.appdef.server.session.Platform;
import org.hyperic.hq.appdef.shared.AppdefDuplicateNameException;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefGroupNotFoundException;
import org.hyperic.hq.appdef.shared.ConfigFetchException;
import org.hyperic.hq.appdef.shared.PlatformNotFoundException;
import org.hyperic.hq.appdef.shared.ServerNotFoundException;
import org.hyperic.hq.appdef.shared.UpdateException;
import org.hyperic.hq.appdef.shared.ValidationException;
import org.hyperic.hq.auth.shared.SessionException;
import org.hyperic.hq.auth.shared.SessionNotFoundException;
import org.hyperic.hq.auth.shared.SessionTimeoutException;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.VetoException;
import org.hyperic.hq.grouping.shared.GroupNotCompatibleException;
import org.hyperic.util.config.EncodingException;

/**
 * Business interface for the CloningBoss EJB
 */
public interface CloningBossInterface {
    
    /**
     * @param subj
     * @param pType platform type
     * @param nameRegEx regex which matches either the platform fqdn or the
     * resource sortname
     */
    public List findPlatformsByTypeAndName(AuthzSubject subj, Integer pType,
                                           String nameRegEx)
    	throws RemoteException;
    
    /**
     * @param subj Method ensures that the master platform has viewable
     * permissions and the clone targets have modifiable permissions.
     * @param platformId master platform id
     * @param cloneTaretIds List<Integer> List of Platform Ids to be cloned
     */
    public void clonePlatform(AuthzSubject subj, Integer platformId,
                              List cloneTargetIds)
        throws SessionNotFoundException, SessionTimeoutException,
               SessionException, PermissionException, PlatformNotFoundException,
               RemoteException;

    /**
     * 
     */
    public void clonePlatform(AuthzSubject subj, Platform master,
                              Platform clone)
        throws AppdefEntityNotFoundException, ConfigFetchException,
               PermissionException, FinderException, CreateException,
               NamingException, SessionNotFoundException,
               SessionTimeoutException, SessionException, VetoException,
               AppdefDuplicateNameException, ValidationException,
               GroupNotCompatibleException, UpdateException, EncodingException,
               RemoteException;
    
}
