/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
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

package org.hyperic.hq.appdef.server.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.ejb.CreateException;
import javax.ejb.FinderException;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.naming.NamingException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.appdef.shared.AppSvcClustDuplicateAssignException;
import org.hyperic.hq.appdef.shared.AppSvcClustIncompatSvcException;
import org.hyperic.hq.appdef.shared.AppdefDuplicateNameException;
import org.hyperic.hq.appdef.shared.AppdefEntityConstants;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEvent;
import org.hyperic.hq.appdef.shared.AppdefGroupNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefGroupValue;
import org.hyperic.hq.appdef.shared.ApplicationNotFoundException;
import org.hyperic.hq.appdef.shared.ApplicationPK;
import org.hyperic.hq.appdef.shared.InvalidAppdefTypeException;
import org.hyperic.hq.appdef.shared.MiniResourceValue;
import org.hyperic.hq.appdef.shared.PlatformNotFoundException;
import org.hyperic.hq.appdef.shared.ServerNotFoundException;
import org.hyperic.hq.appdef.shared.ServerPK;
import org.hyperic.hq.appdef.shared.ServerTypeValue;
import org.hyperic.hq.appdef.shared.ServiceClusterPK;
import org.hyperic.hq.appdef.shared.ServiceClusterValue;
import org.hyperic.hq.appdef.shared.ServiceLightValue;
import org.hyperic.hq.appdef.shared.ServiceNotFoundException;
import org.hyperic.hq.appdef.shared.ServicePK;
import org.hyperic.hq.appdef.shared.ServiceTypePK;
import org.hyperic.hq.appdef.shared.ServiceTypeValue;
import org.hyperic.hq.appdef.shared.ServiceVOHelperUtil;
import org.hyperic.hq.appdef.shared.ServiceValue;
import org.hyperic.hq.appdef.shared.UpdateException;
import org.hyperic.hq.appdef.shared.ValidationException;
import org.hyperic.hq.appdef.AppService;
import org.hyperic.hq.appdef.ServiceCluster;
import org.hyperic.hq.authz.shared.AuthzConstants;
import org.hyperic.hq.authz.shared.AuthzSubjectValue;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.PermissionManager;
import org.hyperic.hq.authz.shared.PermissionManagerFactory;
import org.hyperic.hq.authz.shared.ResourceValue;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.shared.HQConstants;
import org.hyperic.hq.grouping.server.session.GroupUtil;
import org.hyperic.hq.grouping.shared.GroupNotCompatibleException;
import org.hyperic.hq.product.ServiceTypeInfo;
import org.hyperic.util.jdbc.DBUtil;
import org.hyperic.util.pager.PageControl;
import org.hyperic.util.pager.PageList;
import org.hyperic.util.pager.Pager;
import org.hyperic.util.pager.SortAttribute;
import org.hyperic.hibernate.dao.ServiceDAO;
import org.hyperic.hibernate.dao.ServiceTypeDAO;
import org.hyperic.hibernate.dao.ServerTypeDAO;
import org.hyperic.hibernate.dao.AppServiceDAO;
import org.hyperic.hibernate.dao.ApplicationDAO;
import org.hyperic.hibernate.dao.ConfigResponseDAO;
import org.hyperic.hibernate.dao.ServiceClusterDAO;
import org.hyperic.dao.DAOFactory;
import org.hibernate.ObjectNotFoundException;
import org.hyperic.hq.appdef.server.session.Platform;
import org.hyperic.hq.appdef.server.session.Server;
import org.hyperic.hq.appdef.server.session.Service;
import org.hyperic.hq.appdef.server.session.PlatformType;
import org.hyperic.hq.appdef.server.session.ServerType;
import org.hyperic.hq.appdef.server.session.ServiceType;

/**
 * This class is responsible for managing Server objects in appdef
 * and their relationships
 * @ejb:bean name="ServiceManager"
 *      jndi-name="ejb/appdef/ServiceManager"
 *      local-jndi-name="LocalServiceManager"
 *      view-type="local"
 *      type="Stateless"
 * @ejb:util generate="physical"
 * @ejb:transaction type="REQUIRED"
 */
public class ServiceManagerEJBImpl extends AppdefSessionEJB
    implements SessionBean {

    private Log log = LogFactory.getLog(
        "org.hyperic.hq.appdef.server.session.ServiceManagerEJBImpl");

    private final String VALUE_PROCESSOR
        = "org.hyperic.hq.appdef.server.session.PagerProcessor_service";
    private Pager valuePager = null;
    private final Integer APPDEF_RES_TYPE_UNDEFINED = new Integer(-1);

    private static final PermissionManager pm = 
        PermissionManagerFactory.getInstance();

    private Connection getDBConn() throws SQLException {
        try {
            return DBUtil.getConnByContext(this.getInitialContext(), 
                                            HQConstants.DATASOURCE);
        } catch(NamingException exc){
            throw new SystemException("Unable to get database context: " +
                                         exc.getMessage(), exc);
        }
    }

    /**
     * Create a Service which runs on a given server
     * @param ServerPK - the pk of the server hosting the service
     * @param ServiceTypePK - the pk of the service type
     * @param Service - the value object representation of the service
     * @return ServiceValue - the saved value object
     * @exception CreateException - if it fails to add the service
     * @ejb:interface-method
     * @ejb:transaction type="RequiresNew"
     */
    public ServicePK createService(AuthzSubjectValue subject,
        ServerPK spk, ServiceTypePK stpk, ServiceValue sValue)
        throws CreateException, ValidationException, PermissionException,
               ServerNotFoundException, AppdefDuplicateNameException
    {
        if(log.isDebugEnabled()) {
            log.debug("Begin createService: " + sValue);
        }

        try {
            validateNewService(sValue);
            trimStrings(sValue);
            // first we look up the server
            // if this bombs we go no further
            Server sLocal = findServerByPK(spk);
            try {
                // set the service type
                ServiceTypeValue serviceType = ServiceVOHelperUtil.getLocalHome()
                    .create().getServiceTypeValue(stpk);
                sValue.setServiceType(serviceType);    
            } catch (CreateException e) {
                throw new SystemException(e);                
            } 
            sValue.setOwner(subject.getName());
            sValue.setModifiedBy(subject.getName());
            // call the create
            Service service = getServiceDAO().createService(sLocal, sValue);
            
            try {
                if (sLocal.getServerType().getVirtual()) {
                    // Look for the platform authorization
                    createAuthzService(sValue.getName(), 
                                       service.getId(),
                                       sLocal.getPlatform().getId(),
                                       false, 
                                       subject);
                }
                else {
                    // now add the authz resource
                    createAuthzService(sValue.getName(), 
                                       service.getId(),
                                       spk.getId(), 
                                       true, 
                                       subject);
                }
            } catch (CreateException e) {
                // failed to create authz, attempt to remove the service 
//                try {
//                    if(service != null) {
//                        service.remove();
//                    }
//                } catch (RemoveException re) {}
//                rollback();
                throw e;
            }
            
            // remove the server vo from the cache 
            // since the service set has changed
            VOCache.getInstance().removeServer(spk.getId());
            return service.getPrimaryKey();
        } catch (FinderException e) {
            log.error("Unable to find ServiceType", e);
            throw new CreateException("Unable to find ServiceType: " 
                + stpk + " : " + e.getMessage());
        } catch (NamingException e) {
            log.error("Unable to get LocalHome", e);
            throw new SystemException("Unable to get LocalHome " +
                                         e.getMessage());
        } catch (PermissionException e) {
            // make sure that if there is a permission exception during service creation,
            // rollback the whole service creation process; otherwise, there would be
            // a EAM_SERVICE record without its cooresponding EAM_RESOURCE record
            log.error("User: " + subject.getName() 
                + " can not add services to server: " + spk);
            rollback();
            throw e;
        }
    }

    /**
     * Create the Authz service resource
     * @param serviceName 
     * @param serviceId 
     * @param subject - the user creating
     */
    private void createAuthzService(String serviceName,
                                    Integer serviceId,
                                    Integer parentId, boolean isServer, AuthzSubjectValue subject)
        throws CreateException, FinderException, PermissionException {
        log.debug("Begin Authz CreateService");
        // check to see that the user has permission to addServices
        if (isServer) {
            // to the server in question
            checkPermission(subject, getServerResourceType(), parentId,
                            AuthzConstants.serverOpAddService);
        }
        else {
            // to the platform in question
            checkPermission(subject, getPlatformResourceType(), parentId,
                            AuthzConstants.platformOpAddServer);
        }

        createAuthzResource(subject, getServiceResourceType(),
                            serviceId, 
                            serviceName);
        
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServiceValue[] findServicesByName(AuthzSubjectValue subject,
                                             String name)
        throws ServiceNotFoundException, PermissionException
    {
        try {
            List serviceLocals = getServiceDAO().findByName(name);

            int numServices = serviceLocals.size();

            List services = new ArrayList();
            for (int i = 0; i < numServices; i++) {
                Service sLocal = (Service)serviceLocals.get(i);
                ServiceValue sValue = ServiceVOHelperUtil.getLocalHome().
                    create().getServiceValue(sLocal);

                try {
                    checkViewPermission(subject, sValue.getEntityId());
                    services.add(sValue);
                } catch (PermissionException e) {
                    //Ok, won't be added to the list
                }
            }
            
            return (ServiceValue[])services.toArray(new ServiceValue[0]);
    
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
    }

    /**
     * Get service IDs by service type.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list service.
     * @param servTypeId service type id.
     * @return An array of service IDs.
     */
    public Integer[] getServiceIds(AuthzSubjectValue subject,
                                  Integer servTypeId)
        throws PermissionException {
        ServiceDAO sLHome;
        try {
            sLHome = getServiceDAO();
            Collection services = sLHome.findByType(servTypeId);
            if (services.size() == 0) {
                return new Integer[0];
            }
            List serviceIds = new ArrayList(services.size());
         
            // now get the list of PKs
            Collection viewable = super.getViewableServices(subject);
            // and iterate over the ejbList to remove any item not in the
            // viewable list
            int i = 0;
            for (Iterator it = services.iterator(); it.hasNext(); i++) {
                Service aEJB = (Service) it.next();
                if (viewable.contains(aEJB.getPrimaryKey())) {
                    // add the item, user can see it
                    serviceIds.add(aEJB.getId());
                }
            }
        
            return (Integer[]) serviceIds.toArray(new Integer[0]);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            // There are no viewable servers
            return new Integer[0];
        }
    }

    /**
     * @return List of ServiceValue objects
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList findServicesById(AuthzSubjectValue subject,
                                     Integer[] serviceIds, 
                                     PageControl pc) 
        throws ServiceNotFoundException, PermissionException {
        // TODO paging... Not sure if its even needed.
        PageList serviceList = new PageList();
        for(int i = 0; i < serviceIds.length; i++) {
            serviceList.add(getServiceById(subject, serviceIds[i]));
        }
        serviceList.setTotalSize(serviceIds.length);
        return serviceList;
    }

    /**
     * Create a service type supported by a specific server type
     * @ejb:interface-method
     * @ejb:transaction type="RequiresNew"
     */
    public ServiceTypePK createServiceType(AuthzSubjectValue subject,
        ServiceTypeValue stv, ServerTypeValue serverType) 
        throws CreateException, ValidationException {
        try {
            if(log.isDebugEnabled()) {
                log.debug("Begin createServiceType: " +  stv);
            }
            validateNewServiceType(stv, serverType);
            // first look up the parent server
            ServerType servType = getServerTypeDAO()
                .findByPrimaryKey(serverType.getPrimaryKey());
            // now create the service type on it

            ServiceType stype =
                getServiceTypeDAO().createServiceType(servType, stv);
            // flush the cache for the parent server type
            VOCache.getInstance().removeServerType(serverType.getId());
            return stype.getPrimaryKey();
        } catch (ObjectNotFoundException e) {
            throw new CreateException("Unable to find Parent Server Type: " +
                                      e.getMessage());
        }
    }

    /**
     * Find service type by id
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServiceTypeValue findServiceTypeById(Integer id) 
        throws FinderException 
    {
        ServiceTypeValue typeV;
        try {
            typeV = ServiceVOHelperUtil.getLocalHome().create()
                .getServiceTypeValue(new ServiceTypePK(id));
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
        return typeV;
    }

    /**
     * Find service type by name
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServiceTypeValue findServiceTypeByName(String name) 
        throws FinderException {
        ServiceTypeValue typeV;
        try {
            ServiceType st = getServiceTypeDAO().findByName(name);
            if (st == null) {
                throw new FinderException("service type not found: "+ name);
            }
            typeV = ServiceVOHelperUtil.getLocalHome().create()
                .getServiceTypeValue(st);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
        return typeV;
    }

    /**     
     * @return PageList of ServiceTypeValues
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getAllServiceTypes(AuthzSubjectValue subject,
                                       PageControl pc)
    {
        Collection serviceTypes = getServiceTypeDAO().findAll();
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serviceTypes, pc);
    }

    /**     
     * @return List of ServiceTypeValues
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getViewableServiceTypes(AuthzSubjectValue subject,
                                            PageControl pc)
        throws FinderException, PermissionException {
        // build the server types from the visible list of servers
        Collection services;
        try {
            services = getViewableServices(subject, pc);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
        
        Collection serviceTypes = filterResourceTypes(services);
        
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serviceTypes, pc);
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServiceTypesByServerType(AuthzSubjectValue subject,
                                                int serverTypeId) {
        PageControl pc = PageControl.PAGE_ALL;
        Collection serviceTypes =
            getServiceTypeDAO().findByServerType_orderName(serverTypeId, true);
        if (serviceTypes.size() == 0) {
            throw new SystemException(serverTypeId+"");
        }
        return valuePager.seek(serviceTypes, pc);
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList findVirtualServiceTypesByPlatform(AuthzSubjectValue subject,
                                                      Integer platformId) {
        PageControl pc = PageControl.PAGE_ALL;
        Collection serviceTypes = getServiceTypeDAO()
                .findVirtualServiceTypesByPlatform(platformId.intValue());
        if (serviceTypes.size() == 0) {
            throw new SystemException(platformId+"");
        }
        return valuePager.seek(serviceTypes, pc);
    }

    /** 
     * Get service light value by id.  This does not check for permission.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServiceLightValue getServiceLightValue(Integer id)
        throws ServiceNotFoundException, PermissionException {
        try {
            return ServiceVOHelperUtil.getLocalHome().create()
                .getServiceLightValue(new ServicePK(id));
        } catch (FinderException e) {
            throw new ServiceNotFoundException(id, e);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
    }

    /** 
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServiceValue getServiceById(AuthzSubjectValue subject, Integer id)
        throws ServiceNotFoundException, PermissionException {
        try {
            ServiceValue service =
                ServiceVOHelperUtil.getLocalHome().create().getServiceValue(
                    new ServicePK(id));
            checkViewPermission(subject, service.getEntityId());
            return service;
        } catch (CreateException e) {
            throw new SystemException(e);    
        } catch (FinderException e) {
            throw new ServiceNotFoundException(id, e);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    private final String SQL_SERVICE_BY_ID =
        "SELECT RES.ID AS RID, S.ID, T.NAME AS TNAME, S.NAME, S.CTIME " +
        "FROM EAM_SERVICE S, EAM_SERVICE_TYPE T, EAM_RESOURCE RES " + 
        PermissionManager.AUTHZ_FROM + " " +
        "WHERE RES.INSTANCE_ID = S.ID " +
        "AND RES.RESOURCE_TYPE_ID = " + AuthzConstants.authzService + " " +
        "AND S.SERVICE_TYPE_ID = T.ID " +
        "AND S.ID = ? ";
    /**
     * Get a service by id.
     *
     * Unlike it's value object counterpart this method will not throw 
     * permission exceptions, just ServiceNotFoundException.
     *
     * @ejb:transaction type="Required"
     * @ejb:interface-method
     * @param Integer id
     */
    public MiniResourceValue getMiniServiceById(AuthzSubjectValue subject,
                                                Integer id)
        throws ServiceNotFoundException
    {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String sql =
            SQL_SERVICE_BY_ID +
            pm.getSQLWhere(subject.getId(), "S.ID");

        try {
            conn = getDBConn();
            ps = conn.prepareStatement(sql);
            
            ps.setInt(1, id.intValue());
            pm.prepareSQL(ps, 2, 
                          subject.getId(),
                          AuthzConstants.authzService,
                          AuthzConstants.perm_viewService);

            rs = ps.executeQuery();

            if (rs.next()) {
                int col = 1;
                
                MiniResourceValue val =
                    new MiniResourceValue(rs.getInt(col++),
                                          rs.getInt(col++),
                                          AppdefEntityConstants.
                                          APPDEF_TYPE_SERVICE,
                                          rs.getString(col++),
                                          rs.getString(col++),
                                          rs.getLong(col++));

                return val;
            } else {
                // XXX: Could retry the query here without the authz to
                //      see if it's a permissions issue.
                throw new ServiceNotFoundException("Service " + id +
                                                   " not found");
            }
        } catch (SQLException e) {
            throw new SystemException("Error looking up service by id: " +
                                         e, e);
        } finally {
            DBUtil.closeJDBCObjects(log, conn, ps, rs);
        }
    }

    private final String SQL_SERVICES_BY_SERVER =
        "SELECT RES.ID AS RID, S.ID, T.NAME AS TNAME, S.NAME, S.CTIME " +
        "FROM EAM_SERVICE S, EAM_SERVICE_TYPE T, EAM_RESOURCE RES " +
        PermissionManager.AUTHZ_FROM + " " +
        "WHERE RES.INSTANCE_ID = S.ID " +
        "AND RES.RESOURCE_TYPE_ID = " + AuthzConstants.authzService + " " +
        "AND S.SERVICE_TYPE_ID = T.ID " +
        "AND S.CTIME > ? " +
        "AND S.SERVER_ID = ? ";
    /**
     * Get services by server.
     *
     * Unlike it's value object counterpart this method will not throw 
     * permission exceptions.  This method is also not capable of 
     * filtering on service types, though it would be easy to add.
     *
     * @ejb:transaction type="Required"
     * @ejb:interface-method
     */
    public PageList
        getMiniServicesByServer(AuthzSubjectValue subject,
                                Integer sid,
                                long ts,
                                PageControl pc)
    {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String sqlOrder = "";
        int seekCount, i;

        // SQL string for page control ordering.
        int attr = pc.getSortattribute();

        switch(attr) {
        case SortAttribute.RESOURCE_NAME:
            if (pc != null && pc.isDescending()) {
                sqlOrder = " ORDER BY NAME DESC ";
            } else {
                sqlOrder = " ORDER BY NAME ";
            }
            break;
        case SortAttribute.CTIME:
            if (pc != null && pc.isDescending()) {
                sqlOrder = " ORDER BY CTIME DESC ";
            } else {
                sqlOrder = " ORDER BY CTIME ";
            }
        default:
            // No sorting
            break;
        }

        String sql = SQL_SERVICES_BY_SERVER +
            pm.getSQLWhere(subject.getId(), "S.ID") +
            sqlOrder;

        PageList services = new PageList();

        try {
            conn = getDBConn();
            ps = conn.prepareStatement(sql);
            
            ps.setLong(1, ts);
            ps.setInt(2, sid.intValue());
            pm.prepareSQL(ps, 3,
                          subject.getId(),
                          AuthzConstants.authzService,
                          AuthzConstants.perm_viewService);

            rs = ps.executeQuery();
            seekCount = DBUtil.seek(rs, pc);
            int pageSize = pc.getPagesize();
            boolean isUnlimited = (pageSize == PageControl.SIZE_UNLIMITED);
            for (i = 0; (isUnlimited || i<pageSize) && rs.next(); i++) {
                int col = 1;
                
                MiniResourceValue val =
                    new MiniResourceValue(rs.getInt(col++),
                                          rs.getInt(col++),
                                          AppdefEntityConstants.
                                          APPDEF_TYPE_SERVICE,
                                          rs.getString(col++),
                                          rs.getString(col++),
                                          rs.getLong(col++));

                services.add(val);
            } 
            int totalSize = DBUtil.countRows(seekCount+i, rs, conn);
            services.setTotalSize(totalSize);

        } catch (SQLException e) {
            throw new SystemException("Error looking up services by " +
                                         "server: " + e, e);
        } finally {
            DBUtil.closeJDBCObjects(log, conn, ps, rs);
        }

        return services;
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @return A List of ServiceValue objects representing all of the
     * services that the given subject is allowed to view.
     */
    public PageList getAllServices(AuthzSubjectValue subject, PageControl pc)
        throws FinderException, PermissionException {
            
        Collection toBePaged = new ArrayList();
        try {
            toBePaged = getViewableServices(subject, pc);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
        
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(toBePaged, pc);
    }

    /**
     * Get the scope of viewable services for a given user
     * @return List of ServiceLocals for which subject has 
     * AuthzConstants.serviceOpViewService
     */
    private Collection getViewableServices(AuthzSubjectValue subject,
                                            PageControl pc)
        throws NamingException, FinderException, 
               PermissionException {
        Collection toBePaged = new ArrayList();
        // get list of pks user can view
        List authzPks = getViewableServices(subject);
        Collection services = null;
        pc = PageControl.initDefaults(pc, SortAttribute.RESOURCE_NAME);
        
        switch( pc.getSortattribute() ) {
            case SortAttribute.RESOURCE_NAME:
                if(pc != null) {
                    services =
                        getServiceDAO().findAll_orderName(!pc.isDescending());
                }
                break;
            case SortAttribute.SERVICE_NAME:
                if(pc != null) {
                    services =
                        getServiceDAO().findAll_orderName(!pc.isDescending());
                }
                break;
            case SortAttribute.CTIME:
                if(pc != null) {
                    services =
                        getServiceDAO().findAll_orderCtime(!pc.isDescending());
                }
                break;
            default:
                services = getServiceDAO().findAll();
                break;
        }
        for(Iterator i = services.iterator(); i.hasNext();) {
            Service aService = (Service)i.next();
            // remove service if its not viewable
            if(authzPks.contains(aService.getPrimaryKey())) {
                toBePaged.add(aService);
            }
        }
        return toBePaged;
    }

    /**
     * Get all cluster unassigned services - services that haven't been assigned 
     * to a service cluster.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @return A List of ServiceValue objects representing all of the
     * unassigned services that the given subject is allowed to view.
     */
    public PageList getAllClusterUnassignedServices(AuthzSubjectValue subject, 
        PageControl pc) throws FinderException, PermissionException {
        try {
            // get list of pks user can view
            List authzPks = getViewableServices(subject);
            Collection services = null;
            Collection toBePaged = new ArrayList();
            pc = PageControl.initDefaults(pc, SortAttribute.RESOURCE_NAME);

            switch( pc.getSortattribute() ) {
                case SortAttribute.RESOURCE_NAME:
                    if(pc != null) {
                        services = getServiceDAO()
                            .findAllClusterUnassigned_orderName(!pc.isDescending());
                    }
                    break;
                case SortAttribute.SERVICE_NAME:
                    if(pc != null) {
                        services = getServiceDAO()
                            .findAllClusterUnassigned_orderName(!pc.isDescending());
                    }
                    break;
                default:
                    services = getServiceDAO()
                        .findAllClusterUnassigned_orderName_asc();
                    break;
            }
            for(Iterator i = services.iterator(); i.hasNext();) {
                Service aService = (Service)i.next();
                // remove service if its not viewable
                if(authzPks.contains(aService.getPrimaryKey())) {
                    toBePaged.add(aService);
                }
            }
            // valuePager converts local/remote interfaces to value objects
            // as it pages through them.
            return valuePager.seek(toBePaged, pc);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    /**
     * Fetch all services that haven't been assigned to a cluster and that
     * haven't been assigned to any applications.
     * @return A List of ServiceValue objects representing all of the
     * unassigned services that the given subject is allowed to view.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getAllClusterAppUnassignedServices(AuthzSubjectValue subject, 
        PageControl pc) throws FinderException, PermissionException {
        try {
            // get list of pks user can view
            List authzPks = getViewableServices(subject);
            Collection services = null;
            Collection toBePaged = new ArrayList();
            pc = PageControl.initDefaults(pc, SortAttribute.RESOURCE_NAME);

            switch( pc.getSortattribute() ) {
                case SortAttribute.RESOURCE_NAME:
                    if(pc != null) {
                        services = getServiceDAO()
                            .findAllClusterAppUnassigned_orderName(!pc.isDescending());
                    }
                    break;
                case SortAttribute.SERVICE_NAME:
                    if(pc != null) {
                        services = getServiceDAO()
                            .findAllClusterAppUnassigned_orderName(!pc.isDescending());
                    }
                    break;
                default:
                    services = getServiceDAO()
                        .findAllClusterAppUnassigned_orderName_asc();
                    break;
            }
            for(Iterator i = services.iterator(); i.hasNext();) {
                Service aService = (Service)i.next();
                // remove service if its not viewable
                if(authzPks.contains(aService.getPrimaryKey())) {
                    toBePaged.add(aService);
                }
            }
            // valuePager converts local/remote interfaces to value objects
            // as it pages through them.
            return valuePager.seek(toBePaged, pc);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    private PageList filterAndPage(Collection svcCol,
                                   AuthzSubjectValue subject,
                                   Integer svcTypeId, PageControl pc)
        throws ServiceNotFoundException, PermissionException {
        List services = new ArrayList();    
        // iterate over the services and only include those whose pk is 
        // present in the viewablePKs list
        if (svcTypeId != null && svcTypeId != APPDEF_RES_TYPE_UNDEFINED) {
            for (Iterator it = svcCol.iterator(); it.hasNext(); ) {
                Object o = it.next();                
                Integer thisSvcTypeId;
                if (o instanceof Service) {
                    thisSvcTypeId = ((Service)o).getServiceType().getId();
                } else {
                    ServiceCluster cluster = (ServiceCluster)o;
                    thisSvcTypeId = cluster.getServiceType().getId();
                }                
                // first, if they specified a server type, then filter on it
                if (!(thisSvcTypeId.equals(svcTypeId)))
                    continue;
                services.add(o);
            }
        } else {
            services.addAll(svcCol);
        }
        
        List toBePaged = filterUnviewable(subject, services);
        return valuePager.seek(toBePaged, pc);
    }

    private List filterUnviewable(AuthzSubjectValue subject,
                                  Collection services)
        throws PermissionException, ServiceNotFoundException {
        List viewableEntityIds;
        try {
            viewableEntityIds = this.getViewableServiceInventory(subject);
        } catch (FinderException e) {
            throw new ServiceNotFoundException(
                "no viewable services for " + subject);
        } catch (NamingException e) {
            throw new ServiceNotFoundException(
                "no viewable services for " + subject);
        }
    
        List retVal = new ArrayList();
        // if a cluster has some members that aren't viewable then
        // the user can't get at them but we don't worry about it here
        // when the cluster members are accessed, the group subsystem
        // will filter them
        // so here's the case for the ServiceLocal amongst the 
        // List of services
        // ***************** 
        // Note: yes, that's the case with regard to group members,
        // but not groups themselves. Clusters still need to be weeded
        // out here. - desmond
        for (Iterator iter = services.iterator(); iter.hasNext();) {
            Object o = iter.next();
            if (o instanceof Service) {
                Service aService = (Service)o;
                if (viewableEntityIds != null &&
                    viewableEntityIds.contains(aService.getEntityId())) {
                    retVal.add(o);
                }
            }
            else if (o instanceof ServiceCluster) {
                ServiceCluster aCluster = (ServiceCluster)o;
                AppdefEntityID clusterId = new AppdefEntityID(
                    AppdefEntityConstants.APPDEF_TYPE_GROUP,aCluster.getGroupId());
                if (viewableEntityIds != null &&
                    viewableEntityIds.contains(clusterId)) {
                    retVal.add(o);
                }
            }
        }
        return retVal;
    }

    /**
     * Get services by server and type.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByServer(AuthzSubjectValue subject,
                                        Integer serverId, PageControl pc) 
        throws ServiceNotFoundException, ServerNotFoundException, 
               PermissionException {
        return this.getServicesByServer(subject, serverId,
                                        this.APPDEF_RES_TYPE_UNDEFINED, pc);
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByServer(AuthzSubjectValue subject,
                                        Integer serverId, Integer svcTypeId,
                                        PageControl pc) 
        throws ServiceNotFoundException, PermissionException {
        if (svcTypeId == null)
            svcTypeId = APPDEF_RES_TYPE_UNDEFINED;

        List services;

        switch (pc.getSortattribute()) {
        case SortAttribute.SERVICE_TYPE:
            services =
                getServiceDAO().findByServer_orderType(serverId);
            break;
        case SortAttribute.SERVICE_NAME:
        default:
            if (svcTypeId != APPDEF_RES_TYPE_UNDEFINED) {
                services =
                    getServiceDAO().findByServerAndType_orderName(
                        serverId, svcTypeId);
            }
            else {
                services =
                    getServiceDAO().findByServer_orderName(
                        serverId);
            }
            break;
        }
        if (services.size() == 0) {
            throw new ServiceNotFoundException(
                "No services found for server " +
                serverId + " of type " + svcTypeId);
        }
        // Reverse the list if descending
        if (pc != null && pc.isDescending()) {
            Collections.reverse(services);
        }
            
        List toBePaged = filterUnviewable(subject, services);
        return valuePager.seek(toBePaged, pc);
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public Integer[] getServiceIdsByServer(AuthzSubjectValue subject,
                                          Integer serverId, Integer svcTypeId) 
        throws ServiceNotFoundException, PermissionException {
        if (svcTypeId == null)
            svcTypeId = APPDEF_RES_TYPE_UNDEFINED;
 
        List services;
            
        if (svcTypeId == APPDEF_RES_TYPE_UNDEFINED) {
            services = getServiceDAO().findByServer_orderType(serverId);
        }
        else {
            services = getServiceDAO()
                .findByServerAndType_orderName(serverId, svcTypeId);
        }
        if (services.size() == 0) {
            throw new ServiceNotFoundException(
                "No services found for server " +
                serverId + " of type " + svcTypeId);
        }

        // Filter the unviewables
        List viewables = filterUnviewable(subject, services);

        Integer[] ids = new Integer[viewables.size()];
        Iterator it = viewables.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Service local = (Service) it.next();
            ids[i] = local.getId();
        }
            
        return ids;
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public List getServicesByType(AuthzSubjectValue subject,
                                  Integer svcTypeId) 
        throws PermissionException {
        if (svcTypeId == null)
            svcTypeId = APPDEF_RES_TYPE_UNDEFINED;
    
        try {
            Collection services = getServiceDAO().findByType(svcTypeId);
            if (services.size() == 0) {
                return new ArrayList(0);
            }
            List toBePaged = filterUnviewable(subject, services);
            return valuePager.seek(toBePaged, PageControl.PAGE_ALL);
        } catch (ServiceNotFoundException e) {
            return new ArrayList(0);
        }
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByService(AuthzSubjectValue subject,
                                         Integer serviceId, PageControl pc) 
        throws ServiceNotFoundException, PermissionException {
        return this.getServicesByService(subject, serviceId,
                                         this.APPDEF_RES_TYPE_UNDEFINED, pc);
    }
    
    /**
     * Get services by server.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByService(AuthzSubjectValue subject,
                                         Integer serviceId, Integer svcTypeId,
                                         PageControl pc) 
        throws ServiceNotFoundException, PermissionException {
            // find any children
        Collection childSvcs =
            getServiceDAO().findByParentAndType(serviceId, svcTypeId);
        return this.filterAndPage(childSvcs, subject, svcTypeId, pc);
    }

    /**
     * Get service IDs by service.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public Integer[] getServiceIdsByService(AuthzSubjectValue subject,
                                            Integer serviceId,
                                            Integer svcTypeId) 
        throws ServiceNotFoundException, PermissionException {
        // find any children
        Collection childSvcs =
            getServiceDAO().findByParentAndType(serviceId, svcTypeId);
        
        List viewables = this.filterUnviewable(subject, childSvcs);
             
        Integer[] ids = new Integer[viewables.size()];
        Iterator it = viewables.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Service local = (Service) it.next();
            ids[i] = local.getId();
        }
            
        return ids;
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByPlatform(AuthzSubjectValue subject,
                                          Integer platId, PageControl pc) 
        throws ServiceNotFoundException, PlatformNotFoundException, 
               PermissionException {
        return this.getServicesByPlatform(subject, platId,
                                          this.APPDEF_RES_TYPE_UNDEFINED, pc);
    }

    /**
     * Get platform services (children of virtual servers)
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getPlatformServices(AuthzSubjectValue subject,
                                        Integer platId, 
                                        PageControl pc)
        throws PlatformNotFoundException, 
               PermissionException, 
               ServiceNotFoundException {    
        return getPlatformServices(subject, platId, this.APPDEF_RES_TYPE_UNDEFINED, pc);
    }
    
    /**
     * Get platform services (children of virtual servers)
     * of a specified type
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getPlatformServices(AuthzSubjectValue subject,
                                        Integer platId, 
                                        Integer typeId,
                                        PageControl pc)
        throws PlatformNotFoundException, PermissionException, 
               ServiceNotFoundException {
        pc = PageControl.initDefaults(pc, SortAttribute.SERVICE_NAME);
        Collection allServices = getServiceDAO()
            .findPlatformServices_orderName(platId,true,pc.isAscending());
        return this.filterAndPage(allServices, subject, typeId, pc);
    }
    
    /**
     * Get platform services (children of virtual servers), mapped by type id
     * of a specified type
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public Map getMappedPlatformServices(AuthzSubjectValue subject,
                                         Integer platId, 
                                         PageControl pc)
        throws PlatformNotFoundException, PermissionException, 
               ServiceNotFoundException
    {
        pc = PageControl.initDefaults(pc, SortAttribute.SERVICE_NAME);
            
        Collection allServices = getServiceDAO()
            .findPlatformServices_orderName(platId,true,pc.isAscending());
        HashMap retMap = new HashMap();
            
        // Map all services by type ID
        for (Iterator it = allServices.iterator(); it.hasNext(); ) {
            Service svc = (Service) it.next();
            Integer typeId = svc.getServiceType().getId();
            List addTo = (List) retMap.get(typeId);
                
            if (addTo == null) {
                addTo = new ArrayList();
                retMap.put(typeId, addTo);
            }
                
            addTo.add(svc);
        }
            
        // Page the lists before returning
        for (Iterator it = retMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry entry = (Map.Entry) it.next();
            Integer typeId = (Integer) entry.getKey();
            List svcs = (List) entry.getValue();
                
            PageControl pcCheck =
                svcs.size() <= pc.getPagesize() ? PageControl.PAGE_ALL : pc;
                    
            svcs = this.filterAndPage(svcs, subject, typeId, pcCheck);
            entry.setValue(svcs);
        }
            
        return retMap;
    }
    
    /**
     * Get services by platform.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByPlatform(AuthzSubjectValue subject,
                                          Integer platId, Integer svcTypeId,
                                          PageControl pc) 
        throws ServiceNotFoundException, PlatformNotFoundException, 
               PermissionException 
    {
        Collection allServices;
        pc = PageControl.initDefaults(pc,SortAttribute.SERVICE_NAME);

        switch (pc.getSortattribute()) {
        case SortAttribute.SERVICE_NAME:
            allServices = getServiceDAO()
                .findByPlatform_orderName(platId, pc.isAscending());
            break;
        case SortAttribute.SERVICE_TYPE:
            allServices = getServiceDAO()
                .findByPlatform_orderType(platId, pc.isAscending());
            break;
        default:
            throw new IllegalArgumentException("Invalid sort attribute");
        }
        return this.filterAndPage(allServices, subject, svcTypeId, pc);
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @return A List of ServiceValue and ServiceClusterValue objects 
     * representing all of the services that the given subject is allowed to view.
     */
    public PageList getServicesByApplication(AuthzSubjectValue subject,
                                             Integer appId, PageControl pc ) 
        throws ApplicationNotFoundException, ServiceNotFoundException,
               PermissionException {
        return this.getServicesByApplication(subject, appId,
                                          this.APPDEF_RES_TYPE_UNDEFINED, pc);
    }
    
    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @return A List of ServiceValue and ServiceClusterValue objects 
     * representing all of the services that the given subject is allowed to view.
     * @throws ApplicationNotFoundException if the appId is bogus
     * @throws ServiceNotFoundException if services could not be looked up
     */
    public PageList getServicesByApplication(AuthzSubjectValue subject,
                                             Integer appId, Integer svcTypeId,
                                             PageControl pc ) 
        throws PermissionException, ApplicationNotFoundException,
               ServiceNotFoundException {

        try {
            // we only look up the application to validate
            // the appId param
            getApplicationDAO().findById(appId);
        } catch (ObjectNotFoundException e) {
            throw new ApplicationNotFoundException(appId, e);
        }

        Collection appServiceCollection;
        AppServiceDAO appServLocHome =
            DAOFactory.getDAOFactory().getAppServiceDAO();
        pc = PageControl.initDefaults (pc, SortAttribute.SERVICE_NAME);

        switch (pc.getSortattribute()) {
        case SortAttribute.SERVICE_NAME :
            appServiceCollection = appServLocHome
                .findByApplication_orderSvcName(appId,pc.isAscending());
            break;
        case SortAttribute.RESOURCE_NAME :
            appServiceCollection = appServLocHome
                .findByApplication_orderSvcName(appId,pc.isAscending());
            break;
        case SortAttribute.SERVICE_TYPE :
            appServiceCollection = appServLocHome
                .findByApplication_orderSvcType(appId,pc.isAscending());
            break;
        default:
            throw new IllegalArgumentException("Unsupported sort " +
                                               "attribute ["+ pc.getSortattribute() +
                                               "] on PageControl : " + pc);
        }
        AppService appService;
        Iterator i = appServiceCollection.iterator();
        List services = new ArrayList();
        while ( i.hasNext() ) {
            appService = (AppService) i.next();
            if ( appService.getIsCluster() ) {
                services.add(appService.getServiceCluster());
            } else {
                services.add(appService.getService());
            }
        }
        return this.filterAndPage(services, subject, svcTypeId, pc);
   }

   /**
    * @ejb:interface-method
    * @ejb:transaction type="Required"
    * @return A List of ServiceValue and ServiceClusterValue objects 
    * representing all of the services that the given subject is allowed to view.
    */
   public PageList getServiceInventoryByApplication(AuthzSubjectValue subject,
                                            Integer appId, PageControl pc ) 
        throws ApplicationNotFoundException, ServiceNotFoundException,
               PermissionException {
        return getServiceInventoryByApplication(subject, appId,
                                                APPDEF_RES_TYPE_UNDEFINED, pc);
    }

    /**
     * Get all services by application.  This is to only be used for the
     * Evident API.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList 
        getFlattenedServicesByApplication(AuthzSubjectValue subject,
                                          Integer appId,
                                          Integer typeId,
                                          PageControl pc) 
        throws ApplicationNotFoundException, ServiceNotFoundException,
               PermissionException
    {
        if (typeId == null)
            typeId = APPDEF_RES_TYPE_UNDEFINED;

        ApplicationDAO appLocalHome = getApplicationDAO();

        Application appLocal;
        try {
            appLocal = appLocalHome.findByPrimaryKey(new ApplicationPK(appId));
        } catch(ObjectNotFoundException e){
            throw new ApplicationNotFoundException(appId, e);
        }

        Collection svcCollection = new ArrayList();
        Collection appSvcCollection = appLocal.getAppServices();
        Iterator it = appSvcCollection.iterator();
        while (it != null && it.hasNext()) {
            AppService appService = (AppService) it.next();

            if (appService.getIsCluster()) {
                svcCollection.addAll(
                    appService.getServiceCluster().getServices());
            } else {
                svcCollection.add(appService.getService());
            } 
        }

        return this.filterAndPage(svcCollection, subject, typeId, pc);
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @return A List of ServiceValue and ServiceClusterValue objects 
     * representing all of the services that the given subject is allowed to view.
     */
    public PageList getServiceInventoryByApplication(AuthzSubjectValue subject,
                                                     Integer appId,
                                                     Integer svcTypeId,
                                                     PageControl pc ) 
        throws ApplicationNotFoundException, ServiceNotFoundException,
               PermissionException {
        if (svcTypeId == null || svcTypeId.equals(APPDEF_RES_TYPE_UNDEFINED)) {
            List services = getUnflattenedServiceInventoryByApplication(
                    subject, appId, pc);
            return this.filterAndPage(services, subject,
                                      APPDEF_RES_TYPE_UNDEFINED, pc);
        } else {
            return getFlattenedServicesByApplication(subject, appId, svcTypeId,
                                                     pc);
        }
    }

    /**
     * Get all service inventory by application, including those inside an
     * associated cluster
     * 
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * 
     * @param subject
     *            The subject trying to list services.
     * @param appId
     *            Application id.
     * @return A List of ServiceValue objects representing all of the services
     *         that the given subject is allowed to view.
     */
    public ServicePK[] getFlattenedServiceIdsByApplication(
        AuthzSubjectValue subject, Integer appId) 
        throws ServiceNotFoundException, PermissionException,
               ApplicationNotFoundException {

        List serviceInventory = 
            getUnflattenedServiceInventoryByApplication(subject, appId,
                                                        PageControl.PAGE_ALL);
        
        List servicePKs = new ArrayList();
        // flattening: open up all of the groups (if any) and get their services as well
        try {        
            for (Iterator iter = serviceInventory.iterator(); iter.hasNext();) {
                Object o = iter.next();
                // applications can have both clusters and services
                if (o instanceof Service) {
                    Service service = (Service) o;
                    // servers will only have these
                    servicePKs.add(service.getPrimaryKey());
                } else {
                    // this only happens when entId is for an application and
                    // a cluster is bound to it
                    ServiceCluster cluster = (ServiceCluster) o;
                    AppdefEntityID groupId = 
                        new AppdefEntityID(
                            AppdefEntityConstants.APPDEF_TYPE_GROUP, 
                            cluster.getGroupId().intValue());
                    // any authz resource filtering on the group members happens
                    // inside the group subsystem
                    try {
                        List memberIds = GroupUtil.getCompatGroupMembers(
                            subject, groupId, null, PageControl.PAGE_ALL);
                        for (Iterator memberIter = memberIds.iterator();
                             memberIter.hasNext(); ) {
                            AppdefEntityID memberEntId =
                                (AppdefEntityID) memberIter.next();
                            servicePKs.add(memberEntId.getServicePK());                        
                        }
                    } catch (PermissionException e) {
                        // User not allowed to see this group
                        log.debug("User " + subject + " not allowed to view " +
                                  "group " + groupId);
                    }
                }            
            }
        } catch (GroupNotCompatibleException e){
            throw new InvalidAppdefTypeException(
                "serviceInventory has groups that are not compatible", e);
        } catch (AppdefEntityNotFoundException e) {
            throw new ServiceNotFoundException("could not return all services",
                                               e);
        }

        return (ServicePK[]) servicePKs.toArray(
            new ServicePK[servicePKs.size()]);
    }

    private List getUnflattenedServiceInventoryByApplication(
        AuthzSubjectValue subject, Integer appId, PageControl pc)
        throws ApplicationNotFoundException, ServiceNotFoundException {
        
        ApplicationDAO appLocalHome;
        AppServiceDAO appServLocHome;
        List appServiceCollection;
        Application appLocal;

        try {
            appLocalHome = getApplicationDAO();
            appServLocHome = DAOFactory.getDAOFactory().getAppServiceDAO();
            appLocal = appLocalHome.findByPrimaryKey(new ApplicationPK(appId));
        } catch (ObjectNotFoundException e) {
            throw new ApplicationNotFoundException(appId, e);
        }
        // appServiceCollection = appLocal.getAppServices();

        pc = PageControl.initDefaults(pc, SortAttribute.SERVICE_NAME);

        switch (pc.getSortattribute()) {
        case SortAttribute.SERVICE_NAME :
        case SortAttribute.RESOURCE_NAME :
            appServiceCollection =
                appServLocHome.findByApplication_orderName(appId);
            break;
        case SortAttribute.SERVICE_TYPE :
            appServiceCollection =
                appServLocHome.findByApplication_orderType(appId);
            break;
        default :
            throw new IllegalArgumentException(
                "Unsupported sort attribute [" + pc.getSortattribute() +
                "] on PageControl : " + pc);
        }
        if (appServiceCollection.size() == 0) {
            throw new ServiceNotFoundException(
                "No (viewable) services "
                + "associated with application "
                + appId);
        }
        if (pc.isDescending())
            Collections.reverse(appServiceCollection);

        // XXX Call to authz, get the collection of all services
        // that we are allowed to see.
        // OR, alternatively, find everything, and then call out
        // to authz in batches to find out which ones we are
        // allowed to return.

        AppService appService;
        Iterator i = appServiceCollection.iterator();
        List services = new ArrayList();
        while (i.hasNext()) {
            appService = (AppService) i.next();
            if (appService.getIsCluster()) {
                services.add(appService.getServiceCluster());
            } else {
                services.add(appService.getService());
            }
        }
        return services;
    }

    /**
     * Private method to validate a new ServiceValue object
     */
    private void validateNewService(ServiceValue sv)
        throws ValidationException {
        String msg = null;
        // first check if its new 
        if(sv.idHasBeenSet()) {
            msg = "This service is not new. It has id: " + sv.getId();
        }
        // else if(someotherthing)  ...

        // Now check if there's a msg set and throw accordingly
        if(msg != null) {
            throw new ValidationException(msg);
        }
    }     

    /**
     * @ejb:interface-method
     * @ejb:transaction type="RequiresNew"
     */
    public ServiceValue updateService(AuthzSubjectValue subject,
                                      ServiceValue existing)
        throws PermissionException, UpdateException, 
               AppdefDuplicateNameException, ServiceNotFoundException {
        try {
            Service service =
                getServiceDAO().findByPrimaryKey(
                    existing.getPrimaryKey());
            checkModifyPermission(subject, service.getEntityId());
            existing.setModifiedBy(subject.getName());
            existing.setMTime(new Long(System.currentTimeMillis()));
            trimStrings(existing);
            if(!existing.getName().equals(service.getName())) {
                ResourceValue rv = getAuthzResource(getServiceResourceType(),
                    existing.getId());
                rv.setName(existing.getName());
                updateAuthzResource(rv);
            }
            if(service.matchesValueObject(existing)) {
                log.debug("No changes found between value object and entity");
                return existing;
            } else {
                service.updateService(existing);
                // flush cache
                VOCache.getInstance().removeService(existing.getId());
                return getServiceById(subject, existing.getId());
            }
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            throw new ServiceNotFoundException(existing.getEntityId());
        }
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRESNEW"
     */
    public void changeServiceOwner(AuthzSubjectValue who,
                                           Integer serviceId,
                                           AuthzSubjectValue newOwner)
        throws FinderException, PermissionException, CreateException {
        try {
            ServicePK aPK = new ServicePK(serviceId);
            // first lookup the service
            Service serviceEJB = getServiceDAO().findById(serviceId);
            // check if the caller can modify this service
            checkModifyPermission(who, serviceEJB.getEntityId());
            // now get its authz resource
            ResourceValue authzRes = getServiceResourceValue(aPK);
            // change the authz owner
            getResourceManager().setResourceOwner(who, authzRes, newOwner);
            // update the owner field in the appdef table -- YUCK
            serviceEJB.setOwner(newOwner.getName());
            serviceEJB.setModifiedBy(who.getName());
            // flush cache
            VOCache.getInstance().removeService(serviceId);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }


    private void validateNewServiceType(ServiceTypeValue stv,
                                        ServerTypeValue serverType) 
        throws ValidationException {

        String msg = null;
        // check if its new
        if(stv.idHasBeenSet()) {
            msg = "This ServiceType is not new. It has id: " + stv.getId();
        }
        else {
            // insert validation here
        }
        if(msg != null) {
            throw new ValidationException(msg);
        }
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRESNEW"
     */
    public void updateServiceTypes(String plugin, ServiceTypeInfo[] infos)
        throws CreateException, FinderException, RemoveException {
        VOCache cache = VOCache.getInstance();
        AuthzSubjectValue overlord = null;
        
        // First, put all of the infos into a Hash
        HashMap infoMap = new HashMap();
        for (int i = 0; i < infos.length; i++) {
            infoMap.put(infos[i].getName(), infos[i]);
        }

        HashMap serverTypes = new HashMap();

        ServiceTypeDAO stLHome = getServiceTypeDAO();
        try {
            Collection curServices = stLHome.findByPlugin(plugin);
            ServerTypeDAO stHome = getServerTypeDAO();
            
            for (Iterator i = curServices.iterator(); i.hasNext();) {
                ServiceType stlocal = (ServiceType) i.next();

                if (log.isDebugEnabled()) {
                    log.debug("Begin updating ServiceTypeLocal: " +
                              stlocal.getName());
                }

                ServiceTypeInfo sinfo =
                    (ServiceTypeInfo) infoMap.remove(stlocal.getName());

                // See if this exists
                if (sinfo == null) {
                    // Get overlord
                    if (overlord == null)
                        overlord = getOverlord();
                    
                    // Remove all services
                    for (Iterator svcIt = stlocal.getServices().iterator();
                         svcIt.hasNext(); ) {
                        Service svcLocal = (Service) svcIt.next();
                        try {
                            removeService(overlord, svcLocal, true);
                        } catch (PermissionException e) {
                            // This should never happen, we're the overlord
                            throw new SystemException(e);
                        }
                    }
                    
                    cache.removeServiceType(stlocal.getId());
                    stLHome.remove(stlocal);
                } else {
                    // Just update it
                    // XXX TODO MOVE THIS INTO THE ENTITY
                    if (!sinfo.getName().equals(stlocal.getName()))
                        stlocal.setName(sinfo.getName());
                        
                    if (!sinfo.getDescription().equals(
                        stlocal.getDescription()))
                        stlocal.setDescription(sinfo.getDescription());
                    
                    if (sinfo.getInternal() !=  stlocal.getIsInternal())
                        stlocal.setIsInternal(sinfo.getInternal());
                    // flush cached value of service type
                    cache.removeServiceType(
                        stlocal.getId());
                    // Could be null if servertype was deleted/updated by plugin
                    ServerType svrtype = stlocal.getServerType();

                    // Check server type
                    if (svrtype == null ||
                        !sinfo.getServerName().equals(svrtype.getName())) {
                        // Lookup the server type
                        if (serverTypes.containsKey(sinfo.getServerName()))
                            svrtype = (ServerType)
                                serverTypes.get(sinfo.getServerName());
                        else {
                            svrtype = stHome.findByName(sinfo.getServerName());
                            if (svrtype == null) {
                                throw new FinderException(
                                    "Unable to find server " +
                                    sinfo.getServerName() +
                                    " on which service '" +
                                    stlocal.getName() +
                                    "' relies");
                            }
                            serverTypes.put(svrtype.getName(), svrtype);
                        }
                        stlocal.setServerType(svrtype);
                    }
                }
            }
            
            // Now create the left-overs
            for (Iterator i = infoMap.values().iterator(); i.hasNext();) {
                ServiceTypeInfo sinfo = (ServiceTypeInfo) i.next();

                // Just update it
                ServiceType stype = new ServiceType();
                stype.setPlugin(plugin);
                stype.setName(sinfo.getName());
                stype.setDescription(sinfo.getDescription());
                stype.setIsInternal(sinfo.getInternal());

                // Now create the service type
                ServiceType stlocal = stLHome.create(stype);
                ServiceTypeValue stvo = stlocal.getServiceTypeValue();
                
                // Save it in the VOCache
                cache.put(stvo.getId(), stvo);

                // Lookup the server type
                ServerType servTypeEJB;
                if (serverTypes.containsKey(sinfo.getServerName()))
                    servTypeEJB = (ServerType)
                        serverTypes.get(sinfo.getServerName());
                else {
                    servTypeEJB = stHome.findByName(sinfo.getServerName());
                    serverTypes.put(servTypeEJB.getName(), servTypeEJB);
                }
                stlocal.setServerType(servTypeEJB);
            }

            // expire the server types
            for (Iterator it = serverTypes.values().iterator(); it.hasNext(); )
            {
                ServerType servTypeEJB = (ServerType) it.next();
                cache.removeServerType(servTypeEJB.getId());
            }
        } finally {
            stLHome.getSession().flush();
        }
    }

    /**
     * @param deep - delete child services also
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public void removeService(AuthzSubjectValue subj, Integer serviceId, 
                              boolean deep) 
        throws RemoveException, FinderException, PermissionException {
        Service service;
        service = getServiceDAO().findById(serviceId);
        removeService(subj, service, deep);
    }
  /**
     * A removeService method that takes a ServiceLocal.  This is called by
     * ServerManager.removeServer when cascading a delete onto services.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public void removeService(AuthzSubjectValue subj, Service service,
                              boolean deep)
        throws RemoveException, FinderException, PermissionException {

        ServicePK pk = (ServicePK) service.getPrimaryKey();
        Integer serviceId = pk.getId();
        try {
            // find any children
            Collection childSvcs = getServiceDAO().findByParent(serviceId);
            if ( !deep && childSvcs.size() > 0) {
                throw new RemoveException(
                    "Service can not be removed since it has children");
            }
            ServerPK serverPk = (ServerPK)service.getServer().getPrimaryKey();
            Integer serverId = serverPk.getId();

            // validate permission needs removeService on the service
            // to succeed
            checkRemovePermission(subj, service.getEntityId());
            ResourceValue serviceRv = getServiceResourceValue(pk);
            // remove any child services
            for(Iterator i = childSvcs.iterator(); i.hasNext();) {
                Service child = (Service)i.next();
                Integer childId = child.getId();
                this.removeService(subj, childId, deep);
            }

            // keep the configresponseId so we can remove it later
            Integer cid = service.getConfigResponseId();

            // remove from authz
            this.removeAuthzResource(subj, serviceRv);
            // remove vo from service cache, and from server cache
            VOCache.getInstance().removeService(serviceId);
            VOCache.getInstance().removeServer(serverId);
            // remove from appdef
            getServiceDAO().remove(service);

            // remove the config response
            if (cid != null) {
                try {
                    ConfigResponseDAO cdao = getConfigResponseDAO();
                    cdao.remove(cdao.findById(cid));
                } catch (ObjectNotFoundException e) {
                    // OK, no config response, just log it
                    log.warn("Invalid config ID " + cid);
                }
            }

            // remove custom properties
            deleteCustomProperties(AppdefEntityConstants.APPDEF_TYPE_SERVICE,
                                   serviceId.intValue());

            // Send service deleted event
            sendAppdefEvent(subj, new AppdefEntityID(pk),
                            AppdefEvent.ACTION_DELETE);
        } catch (CreateException e) {
            log.error("Unable to getServiceResourceValue", e);
            throw new RemoveException("Unable to getServiceResourceValue: "
                + e);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    /**
     * Create a service cluster from a set of service Ids
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRESNEW"
     */
    public ServiceClusterPK createCluster(AuthzSubjectValue subj,
                                             ServiceClusterValue cluster,
                                             List serviceIdList)
        throws AppSvcClustDuplicateAssignException, 
               AppSvcClustIncompatSvcException, CreateException {
        // TODO check authz createCluster operation 
        ServiceCluster clusterEJB =
            getServiceClusterDAO().create(cluster, serviceIdList);
        return clusterEJB.getPrimaryKey();
    }
    
    /**
     * @param serviceIdList - the list of service id's which comprise the updated cluster
     * @ejb:interface-method
     * @ejb:transaction type="RequiresNew"
     */
    public void updateCluster(AuthzSubjectValue subj,
                              ServiceClusterValue cluster,
                              List serviceIdList)
        throws AppSvcClustDuplicateAssignException,
               AppSvcClustIncompatSvcException,
               FinderException, PermissionException { 
        // find the cluster
        ServiceCluster clusterEJB =
            getServiceClusterDAO().findById(cluster.getId());
        clusterEJB.updateCluster(cluster, serviceIdList);
    }
    
    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public void removeCluster(AuthzSubjectValue subj, Integer clusterId)
        throws RemoveException, FinderException, PermissionException {
        ServiceCluster clusterLoc =
            getServiceClusterDAO().findById(clusterId);
        // XXX - Authz chex needed?
        //checkRemovePermission(subj, clusterLoc.getEntityId());
        getServiceClusterDAO().remove(clusterLoc);
    }
    
    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServiceClusterValue getClusterById(AuthzSubjectValue subj,
                                              Integer cid)
        throws FinderException, PermissionException {
        // TODO authz        
        return getServiceClusterDAO().findById(cid).getServiceClusterValue();
    }
    
    /**
     * Retrieve all services belonging to a cluster
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServicesByCluster(AuthzSubjectValue subj,
                                         Integer clusterId)
        throws FinderException, PermissionException {
        // TODO AUTHZ
        Collection clustSvcs = DAOFactory.getDAOFactory().getServiceDAO()
            .findByCluster(clusterId);
        PageList page = new PageList();
        page.setTotalSize(clustSvcs.size());
        for(Iterator i = clustSvcs.iterator(); i.hasNext();) {
            Service aSvc = (Service)i.next();
            try {
            page.add(ServiceVOHelperUtil.getLocalHome().create().getServiceValue(aSvc));
            } catch (CreateException e) {
                throw new SystemException(e);
            } catch (NamingException e) {
                throw new SystemException(e);
            }
        }
        return page;
    }

    /**
     * Get all service clusters.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @return A List of ServiceClusterValue objects representing all of the
     * services that the given subject is allowed to view.
     */
    public PageList getAllServiceClusters(AuthzSubjectValue subject, PageControl pc)
        throws FinderException, PermissionException {
        try {
            ServiceClusterDAO clusterLocalHome = getServiceClusterDAO();

            Collection clusters = null;
            Collection toBePaged = new ArrayList();

            // get list of group value objects user can view
            List viewableGroups = null;
            try {
                viewableGroups = getViewableGroups(subject);
            } catch (AppdefGroupNotFoundException e) {
                viewableGroups = new ArrayList();
            }

            pc = PageControl.initDefaults(pc, SortAttribute.RESOURCE_NAME);

            switch( pc.getSortattribute() ) {
                case SortAttribute.RESOURCE_NAME:
                    clusters =
                        clusterLocalHome.findAll_orderName(!pc.isDescending());
                    break;
                case SortAttribute.SERVICE_NAME:
                    clusters =
                        clusterLocalHome.findAll_orderName(!pc.isDescending());
                    break;
                default:
                    clusters = clusterLocalHome.findAll();
                    break;
            }
            // only page cluster if id is assigned to viewable (service) group
            for(Iterator i = clusters.iterator(); i.hasNext();) {
                ServiceCluster aCluster = (ServiceCluster)i.next();
                // only page cluster if it is viewable.
                for (int x=0;x<viewableGroups.size();x++) {
                    AppdefGroupValue thisGroup = 
                        (AppdefGroupValue)viewableGroups.get(x);
                    if (thisGroup.getClusterId() == 
                        aCluster.getId().intValue()) {
                        toBePaged.add(aCluster);
                    }
                }
            }
            // valuePager converts local/remote interfaces to value objects
            // as it pages through them.
            return valuePager.seek(toBePaged, pc);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    public void ejbCreate() throws CreateException {
        try {
            valuePager = Pager.getPager(VALUE_PROCESSOR);
        } catch ( Exception e ) {
            throw new CreateException("Could not create value pager:" + e);
        }
    }
    
    /**
     * Remove all extraneous spaces from string attributes
     * see bug 5278
     */
    private void trimStrings(ServiceValue service) {
        if (service.getDescription() != null)
            service.setDescription(service.getDescription().trim());
        if (service.getLocation() != null)
            service.setLocation(service.getLocation().trim());
        if (service.getName() != null)
            service.setName(service.getName().trim());
    }
    public void ejbRemove() { }
    public void ejbActivate() { }
    public void ejbPassivate() { }
}
