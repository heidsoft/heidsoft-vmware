/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.virtualization.vmware;

import com.sun.enterprise.config.serverbeans.Cluster;
import com.sun.enterprise.config.serverbeans.SecureAdmin;
import com.sun.enterprise.security.ssl.SSLUtils;
import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
import java.io.*;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.util.Map.Entry;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.cluster.ssh.launcher.SSHLauncher;
import org.glassfish.hk2.inject.Injector;
import org.glassfish.virtualization.config.MachineConfig;
import org.glassfish.virtualization.config.VirtUser;
import org.glassfish.virtualization.config.VirtualMachineConfig;
import org.glassfish.virtualization.config.Virtualizations;
import org.glassfish.virtualization.runtime.AbstractMachine;
import org.glassfish.virtualization.runtime.VirtualMachineLifecycle;
import org.glassfish.virtualization.spi.*;
import org.glassfish.virtualization.spi.VirtualMachine;
import org.glassfish.virtualization.util.ListenableFutureImpl;
import org.glassfish.virtualization.util.RuntimeContext;
import org.glassfish.virtualization.vmware.config.VMWareVirtualization;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PostConstruct;

/**
 * Abstraction of a VMware host machine. Compatible with ESX and ESXi.
 *
 * @author Fabien Leroy - fabien.leroy@serli.com
 */
public class VMWareMachine extends AbstractMachine implements PostConstruct {

    //folder on the datastore where the VMs will be stored
    private final Map<String, VMWareVirtualMachine> domains = new HashMap<String, VMWareVirtualMachine>();
    //storing some Managed Objects of the machine
    private ServiceInstance si;
    private Datacenter dc;
    private HostSystem hs;
    private Folder rootFolder;
    private VMWareFileOperations sshFileOperations;
    // So far, IP addresses are static within a single run. we could support changing the IP address eventually.
    private final String ipAddress;
    @Inject
    private SSHLauncher sshLauncher;
    @Inject
    private Virtualizations virtualizations;
    @Inject
    private Habitat services;
    @Inject
    private VirtualMachineLifecycle vmLifecycle;
    @Inject
    com.sun.enterprise.config.serverbeans.Domain domainConfig;
    //Default parameters to create a VM, will be overriden by the parameters of 
    //the template configuration file (if this file exists)
    private static final long memorySizeMB = 512;
    private static final int cpuCount = 1;
    private static final String guestOsId = "otherGuest";
    private static final String netName = "VM Network";

    public static VMWareMachine from(Injector injector, VMWareServerPool group, MachineConfig config, String ipAddress) {
        return injector.inject(new VMWareMachine(group, config, ipAddress));
    }

    protected VMWareMachine(VMWareServerPool group, MachineConfig config, String ipAddress) {
        super(group, config);
        this.ipAddress = ipAddress;
    }

    @Override
    public void postConstruct() {
        setState(isUp() ? VMWareMachine.State.READY : VMWareMachine.State.SUSPENDED);
        super.postConstruct();
    }

    /**
     * Adding a StoragePool is forbidden on VMware machines.
     *
     * @param name Name of the StoragePool.
     * @param capacity Capacity of the StoragePool.
     * @return null
     */
    @Override
    public StoragePool addStoragePool(String name, long capacity) throws VirtException {
        return null;
    }

    /**
     * Get a description of this machine.
     *
     * @return A description of the machine and its virtual machines.
     */
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append("Machine ").append(getName());
        try {
            connection();
            ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
            if (mes == null || mes.length == 0) {
                sb.append(" with no virtual machines defined");
            } else {
                sb.append(" with domains : {");
                for (int i = 0; i < mes.length; i++) {
                    com.vmware.vim25.mo.VirtualMachine vm = (com.vmware.vim25.mo.VirtualMachine) mes[i];
                    sb.append("[ domain:").append(
                            vm.getName()).append(
                            " id:").append(
                            vm.getConfig().getGuestId()).append(
                            " running:").append(
                            vm.getConfig().getGuestFullName()).append(
                            " ] ");
                }
                sb.append("}");
            }
        } catch (Exception e) {
            RuntimeContext.logger.log(Level.SEVERE, "Exception while building " + getName() + "'s description " + e, e);
            throw new RuntimeException(e);
        }
        return sb.toString();
    }

    @Override
    public Collection<? extends VirtualMachine> getVMs() throws VirtException {
        try {
            populateDomains();
        } catch (VirtException e) {
            RuntimeContext.logger.log(Level.SEVERE, "Exception while populating list of domains ", e);
        }
        return domains.values();
    }

    @Override
    public Map<String, ? extends StoragePool> getStoragePools() throws VirtException {
        return null;
    }

    @Override
    public VirtualMachine byName(String name) throws VirtException {
        try {
            populateDomains();
        } catch (VirtException e) {
            RuntimeContext.logger.log(Level.SEVERE, "Exception while populating list of domains ", e);
        }
        return domains.get(name);
    }

    /**
     * Connect to this machine's exposed WebService to manipulate it.
     *
     * @return A ServiceInstance object abstracting the connection to this
     * machine.
     * @throws VirtException when connection fails.
     */
    protected ServiceInstance connection() throws VirtException {

        //not yet connected or session not valid anymore
        if (si == null || si.getSessionManager().getCurrentSession() == null) {
            RuntimeContext.logger.log(Level.FINE, "Establishing a new connection on {0}", getName());

            if (getUser() == null || getUser().getName() == null) {
                throw new VirtException("Could not establish a connection on " + getName() + " because "
                        + "no valid user is defined, execute command \"asadmin help create-machine-user\" to get help");
            }

            // Get Admin SSL context to be able to access GlassFish's truststore
            SecureAdmin secureAdmin = services.forContract(SecureAdmin.class).get();
            services.forContract(SSLUtils.class).get().getAdminSSLContext(SecureAdmin.Util.DASAlias(secureAdmin), "SSL");

            //String used to connect to the hypervisor
            //connectionString looks like : "connnectionUrl?option1=value1&option2=value2"
            String connectionString = getVirtualizationConfig().getConnectionString();
            Map<String, String> options = new HashMap<String, String>();
            boolean ignoreSSL = false;

            String hostName = "";
            try {
                // Finding the DNS name of the machine
                hostName = InetAddress.getByName(getIpAddress()).getHostName();
            } catch (UnknownHostException ex) {
                RuntimeContext.logger.log(Level.WARNING, getIpAddress() + " could not be resolved to a host name,"
                        + " trying to connect with the IP ", ex);
                hostName = getIpAddress();
            }
            connectionString = connectionString.replace("#{target.host}", hostName);

            //handling options in connectionString
            String connectionUrl = "";
            int optionIndex = connectionString.indexOf('?');
            if (optionIndex != -1 && (optionIndex + 1 <= connectionString.length() - 1)) {
                //connection string contains options (of the form "?option=value")
                //extracting url and options
                connectionUrl = connectionString.substring(0, optionIndex);
                String optionString = connectionString.substring(optionIndex + 1, connectionString.length());
                String[] splitOptions = optionString.split("&");
                for (String option : splitOptions) {
                    String[] splitOption = option.split("=");
                    if (splitOption.length == 2) {
                        options.put(splitOption[0].toLowerCase(), splitOption[1]);
                    } else {
                        RuntimeContext.logger.log(Level.WARNING, "Invalid option in the emulator connection string : \"{0}\" will be ignored.", option);
                    }
                }
                String noVerify = options.get("no_verify");
                if (noVerify != null
                        && (noVerify.equalsIgnoreCase("true") || noVerify.equals("1"))) {
                    ignoreSSL = true;
                }
            } else {
                //no option
                connectionUrl = connectionString;
            }

            try {
                //Connecting to the machine
                RuntimeContext.logger.log(Level.FINE, "Connecting to {0} with user: {1}, using url : {2}",
                        new Object[]{getName(), getUser().getName(), connectionUrl});
                si = new ServiceInstance(new URL(connectionUrl), getUser().getName(), getUser().getPassword(), ignoreSSL);
                rootFolder = si.getRootFolder();


                //Store some useful objects, avoid many time consuming function calls later
                ManagedEntity[] datacenters = new InventoryNavigator(rootFolder).searchManagedEntities("Datacenter");
                ManagedEntity[] hostsystems = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem");
                if (datacenters.length == 0 || hostsystems.length == 0) {
                    throw new VirtException("Exception while retrieving the ManagedObjects for " + getName() + ", connection failed");
                }
                dc = (Datacenter) datacenters[0];
                hs = (HostSystem) hostsystems[0];

                sshFileOperations = new VMWareFileOperations(this, getSSH(), si, dc, hs);

            } catch (MalformedURLException ex) {
                si = null;
                throw new VirtException("Could not connect to " + getName() + ", connection url \"" + connectionUrl
                        + "\" calculated from \"" + getVirtualizationConfig().getConnectionString() + "\" is invalid.", ex);
            } catch (Exception ex) {
                si = null;
                throw new VirtException("Could not connect to " + getName(), ex);
            }
        }
        RuntimeContext.logger.log(Level.FINE, "Connection to {0} successful", getName());
        return si;
    }

    /**
     * Update the list of virtual machines (domains) stored in the class
     * attribute @attribute domains.
     *
     * @throws VirtException when the list could not be retrieved because of
     * some connection problem.
     */
    private void populateDomains() throws VirtException {
        try {
            connection();
            //we build the entire list from scratch because 
            //some domains might have been deleted without notification
            domains.clear();
            ManagedEntity[] vms = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
            for (ManagedEntity me : vms) {
                com.vmware.vim25.mo.VirtualMachine vm = (com.vmware.vim25.mo.VirtualMachine) me;
                try {
                    addDomain(vm);
                } catch (Exception e) {
                    //a VM is registered on the host but its files are not accessible
                    //probably datastore was unmounted
                    //just ignore this unusable VM
                }
            }
        } catch (RemoteException ex) {
            throw new VirtException(ex);
        }

    }

    /**
     * Add a new virtual machine in the domains list.
     *
     * @param vm Virual machine to add.
     * @throws VirtException
     */
    private void addDomain(com.vmware.vim25.mo.VirtualMachine vm) throws VirtException {
        String domainName = vm.getName();
        if (!domains.containsKey(domainName)) {
            for (Cluster cluster : domainConfig.getClusters().getCluster()) {
                for (VirtualMachineConfig vmc : cluster.getExtensionsByType(VirtualMachineConfig.class)) {
                    if (vmc.getName().equals(domainName)) {
                        VMWareVirtualMachine gfVM = new VMWareVirtualMachine(vmc, vmc.getTemplate().getUser(), this, vm);
                        domains.put(domainName, gfVM);
                        return;
                    }
                }
            }
            // if we end up, that means this virtual machine is not one managed by this group master
        }
    }

    @Override
    public PhasedFuture<AllocationPhase, VirtualMachine> create(
            final TemplateInstance template,
            final VirtualCluster cluster,
            final EventSource<AllocationPhase> source)
            throws VirtException, IOException {

        connection();

        source.fireEvent(AllocationPhase.VM_PREPARE);

        //Finding a non already used name for the VM
        String vmName = cluster.getConfig().getName() + cluster.allocateToken();
        while ((new InventoryNavigator(rootFolder)).searchManagedEntity("VirtualMachine", vmName) != null) {
            vmName = cluster.getConfig().getName() + cluster.allocateToken();
        }

        // create vm directory (of the name of the VM)
        String vmLocation = getVMPath(vmName);
        sshFileOperations.delete(vmLocation);
        sshFileOperations.mkdir(vmLocation);

        //Find config disk location
        File machineDisks = absolutize(new File(virtualizations.getDisksLocation(), serverPool.getName()));
        machineDisks = new File(machineDisks, getName());

        //create customization file
        File custDirectory = prepareCustDirectory(vmName, cluster.getConfig(), template.getConfig());
        File custFile = new File(machineDisks, vmName + "cust.iso");
        prepareCustomization(custDirectory, custFile, vmName);

        //copy the customization file over.
        sshFileOperations.delete(vmLocation + "/" + custFile.getName());
        sshFileOperations.copy(custFile, new File(vmLocation + "/"));

        //create the virtual machine
        try {
            com.vmware.vim25.mo.VirtualMachine domain = createVM(vmName, template);
            source.fireEvent(AllocationPhase.VM_SPAWN);
            final CountDownLatch latch = vmLifecycle.inStartup(vmName);
            VirtualMachineConfig vmConfig = VirtualMachineConfig.Utils.create(
                    domain.getName(),
                    template.getConfig(),
                    serverPool.getConfig(),
                    cluster.getConfig());

            final VMWareVirtualMachine vm = new VMWareVirtualMachine(vmConfig,
                    template.getConfig().getUser(), this, domain);
            domains.put(vmName, vm);
            cluster.add(vm);

            ListenableFutureImpl<AllocationPhase, VirtualMachine> future =
                    new ListenableFutureImpl<AllocationPhase, VirtualMachine>(latch, vm, source);

            future.fireEvent(AllocationPhase.VM_START);
            vm.start();

            return future;
        } catch (VirtException e) {
            sshFileOperations.delete(vmLocation);
            RuntimeContext.logger.log(Level.SEVERE, "Could not create a new virtual machine on " + getName(), e);
            throw new VirtException(e);
        }

    }

    /**
     * Instantiate a new virtual machine on this machine.
     *
     * @param vmName Name of the machine.
     * @param template Template used to create the virtual machine.
     * @return The newly created virtual machine.
     * @throws VirtException
     */
    private com.vmware.vim25.mo.VirtualMachine createVM(String vmName, TemplateInstance template) throws VirtException {

        //Comment associated to the VM
        String annotation = "VM created by GlassFish Server : " + getName() + " is member of " + getServerPool().getName();

        String templateName = template.getConfig().getName();
        try {
            //Create the default VM
            RuntimeContext.logger.log(Level.FINE, "Creating the virtual machine {0} on {1}", new Object[]{vmName, getName()});
            Folder vmFolder = dc.getVmFolder();
            ManagedEntity[] resourcePools = new InventoryNavigator(
                    dc).searchManagedEntities("ResourcePool");
            if (resourcePools.length == 0) {
                throw new VirtException("Exception while retrieving the ManagedObjects for " + getName() + ", " + vmName + " could not be created");
            }
            ResourcePool rp = (ResourcePool) resourcePools[0];
            // create vm config spec
            VirtualMachineConfigSpec vmSpec = new VirtualMachineConfigSpec();
            vmSpec.setName(vmName);
            vmSpec.setMemoryMB(memorySizeMB);
            vmSpec.setNumCPUs(cpuCount);
            vmSpec.setGuestId(guestOsId);
            vmSpec.setUuid(UUID.randomUUID().toString());
            vmSpec.setAnnotation(annotation);
            // accept mac address outside vmware's mac address range
            OptionValue opt = new OptionValue();
            opt.setKey("ethernet0.checkMACAddress");
            opt.setValue("false");
            vmSpec.setExtraConfig(new OptionValue[]{opt});
            // create nic virtual device
            VirtualDeviceConfigSpec[] spec = createNetworkConfigSpec();
            vmSpec.setDeviceChange(spec);
            // create vm file info for the vmx file
            VirtualMachineFileInfo vmfi = new VirtualMachineFileInfo();
            String diskLocation = sshFileOperations.getFullPath(config.getDisksLocation());
            vmfi.setVmPathName(diskLocation);
            vmSpec.setFiles(vmfi);
            // call the createVM_Task method on the vm folder
            Task creationTask = vmFolder.createVM_Task(vmSpec, rp, null);
            String creationResult = creationTask.waitForTask();

            // make a copy of template disk
            // the copy will be used as virtual hard drive on the VM
            copyDisk(vmName, templateName);

            //add cdrom and hard drive, vm must be created beforehand in order to get IDE controller
            com.vmware.vim25.mo.VirtualMachine vm =
                    (com.vmware.vim25.mo.VirtualMachine) new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine", vmName);
            String datastoreName = sshFileOperations.extractDatastoreNameFromPath(diskLocation);
            VirtualDeviceConfigSpec[] deviceSpec = createDevicesConfigSpec(vm, sshFileOperations.getDatastore(datastoreName));
            VirtualMachineConfigSpec vmConfigSpec = new VirtualMachineConfigSpec();
            vmConfigSpec.setDeviceChange(deviceSpec);
            Task updateTask = vm.reconfigVM_Task(vmConfigSpec);
            String updateResult = updateTask.waitForTask();

            if (creationResult.equals(Task.SUCCESS) && updateResult.equals(Task.SUCCESS)) {
                RuntimeContext.logger.log(Level.FINE, "{0} created successfully ", vmName);
            } else {
                throw new VirtException(vmName + " has not been created on " + getName());
            }

            // If needed handle the configuration file associated with the template
            // to customize the VM according to the user's wishes
            File f = template.getFileByExtension("vmx");
            if (f.exists()) {
                //parse the VMX configuration file provided by the user when creating the template, download the VMX file of the vm,
                //merge  this two files in one VMX and upload it as the new VMX of the VM
                customizeVM(vm, f);

                // re-register the VM (else the modified VMX file will not be read)
                vm.unregisterVM();
                Task task = dc.getVmFolder().registerVM_Task(getVMPath(vmName) + "/" + vmName + ".vmx", vmName, false, rp, hs);
                task.waitForTask();
                vm = (com.vmware.vim25.mo.VirtualMachine) new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine", vmName);
                RuntimeContext.logger.log(Level.FINE, "{0} customized successfully ", vmName);
            }

            return vm;
        } catch (Exception ex) {
            try {
                com.vmware.vim25.mo.VirtualMachine vm = (com.vmware.vim25.mo.VirtualMachine) new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine", vmName);
                // VM created but not customized, it has to be deleted
                if (vm != null) {
                    RuntimeContext.logger.log(Level.WARNING, "The VM {0} was created but could not be customized, it will be deleted", vmName);
                    sshFileOperations.delete(getConfig().getDisksLocation() + "/" + vmName + "/" + vmName + "cust.iso");
                    Task task = vm.destroy_Task();
                    task.waitForTask();
                }
            } catch (Exception e) {
                RuntimeContext.logger.log(Level.WARNING, "The VM {0} could not be deleted", vmName);
            } finally {
                throw new VirtException("Exception while creating a new virtual machine on " + getName(), ex);
            }
        }
    }

    /**
     * Create a default E1000 config spec for NIC device.
     *
     * @return A VirtualDeviceConfigSpec array containing only one config spec
     * for NIC device
     */
    private VirtualDeviceConfigSpec[] createNetworkConfigSpec() {

        VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
        nicSpec.setOperation(VirtualDeviceConfigSpecOperation.add);
        VirtualEthernetCard nic = new VirtualE1000();
        VirtualEthernetCardNetworkBackingInfo nicBacking = new VirtualEthernetCardNetworkBackingInfo();
        nicBacking.setDeviceName(netName);
        Description info = new Description();
        info.setSummary(netName);
        info.setLabel("Network Adapter");
        nic.setDeviceInfo(info);
        // type: “generated”, “manual”, “assigned” by VC
        nic.setAddressType("manual");
        nic.setWakeOnLanEnabled(true);
        OsInterface os = services.forContract(OsInterface.class).get();
        //without "ethernet0.checkMacAddress = false" in vmx file range should be : 00:50:56:00:00:00-00:50:56:3f:ff:ff
        nic.setMacAddress(os.macAddressGen());
        nic.setBacking(nicBacking);
        //key of the device, this key should be unique in the VM 
        //but will be changed by the hypervisor anyway
        nic.setKey(0);
        nicSpec.setDevice(nic);

        return new VirtualDeviceConfigSpec[]{nicSpec};
    }

    /**
     * Create config specs for attaching VMDK virtual disk and customization iso
     * file to the VM.
     *
     * @param vm The VM the ISO and VMDK files will be attached to
     * @param ds The Datastore containing the VM and the VMDK and ISO files
     * @return A table containing the config specs needed to attach the devices
     * @throws Exception if an error occurred trying to get the IDE controller
     * of the VM.
     */
    private VirtualDeviceConfigSpec[] createDevicesConfigSpec(com.vmware.vim25.mo.VirtualMachine vm, Datastore ds) throws Exception {
        String vmName = vm.getName();

        // Disk 1 + controller
        VirtualDeviceConfigSpec diskSpec = new VirtualDeviceConfigSpec();
        VirtualDeviceConfigSpec controllerSpec = new VirtualDeviceConfigSpec();

        VirtualLsiLogicController lsiCtrl = new VirtualLsiLogicController();
        lsiCtrl.setKey(1000);
        lsiCtrl.setBusNumber(0);
        lsiCtrl.setSharedBus(VirtualSCSISharing.noSharing);

        controllerSpec.setOperation(VirtualDeviceConfigSpecOperation.add);
        controllerSpec.setDevice(lsiCtrl);

        VirtualDiskFlatVer2BackingInfo diskfileBacking = new VirtualDiskFlatVer2BackingInfo();
        diskfileBacking.setFileName(getVMPath(vmName) + "/" + vmName + ".vmdk");
        diskfileBacking.setDiskMode("persistent");

        VirtualDisk vd = new VirtualDisk();
        //allocating a non already-used key for the device
        vd.setKey(1);
        //allocating unit number for the device on its controller
        vd.setUnitNumber(0);
        vd.setCapacityInKB(1000000);
        vd.setControllerKey(1000);
        vd.setBacking(diskfileBacking);
        diskSpec.setOperation(VirtualDeviceConfigSpecOperation.add); // do not set diskSpec.fileOperation!
        diskSpec.setDevice(vd);


        // CDROM + IDE controller
        VirtualDeviceConfigSpec cdSpec = new VirtualDeviceConfigSpec();

        cdSpec.setOperation(VirtualDeviceConfigSpecOperation.add);

        VirtualCdrom cdrom = new VirtualCdrom();
        VirtualCdromIsoBackingInfo cdDeviceBacking = new VirtualCdromIsoBackingInfo();

        cdDeviceBacking.setDatastore(ds.getMOR());
        String customDiskPath = getVMPath(vmName) + "/" + vmName + "cust.iso";
        cdDeviceBacking.setFileName(customDiskPath);
        VirtualDevice ideController = getIDEController(vm);
        cdrom.setBacking(cdDeviceBacking);
        cdrom.setControllerKey(ideController.getKey());
        cdrom.setUnitNumber(ideController.getUnitNumber());
        cdrom.setKey(-1);

        cdSpec.setDevice(cdrom);

        return new VirtualDeviceConfigSpec[]{controllerSpec, diskSpec, cdSpec};
    }

    /**
     * Retrieve the IDE controller of the (already created) VM.
     *
     * @param vm The VM whose controller is needed.
     * @return The IDE controller of the VM.
     * @throws Exception if an error occurred trying to get the list of
     * controllers of the VM.
     */
    private VirtualDevice getIDEController(com.vmware.vim25.mo.VirtualMachine vm)
            throws Exception {
        VirtualDevice ideController = null;
        VirtualDevice[] defaultDevices = getDefaultDevices(vm);
        for (int i = 0; i < defaultDevices.length; i++) {
            if (defaultDevices[i] instanceof VirtualIDEController) {
                ideController = defaultDevices[i];
                break;
            }
        }
        return ideController;
    }

    /**
     * Retrieve all the controllers of the (already created) VM.
     *
     * @param vm The VM whose controllers are nedeed.
     * @return The list of controllers of the VM.
     * @throws Exception if an error occurred while connecting to the machine or
     * its configuration could not be retrieved.
     */
    private VirtualDevice[] getDefaultDevices(com.vmware.vim25.mo.VirtualMachine vm)
            throws Exception {
        VirtualMachineRuntimeInfo vmRuntimeInfo = vm.getRuntime();
        EnvironmentBrowser envBrowser = vm.getEnvironmentBrowser();
        ManagedObjectReference hmor = vmRuntimeInfo.getHost();
        VirtualMachineConfigOption cfgOpt = envBrowser.queryConfigOption(null, new HostSystem(vm.getServerConnection(), hmor));
        VirtualDevice[] defaultDevs = null;
        if (cfgOpt != null) {
            defaultDevs = cfgOpt.getDefaultDevice();
            if (defaultDevs == null) {
                throw new Exception("No Datastore found in ComputeResource");
            }
        } else {
            throw new Exception("No VirtualHardwareInfo found in ComputeResource");
        }
        return defaultDevs;
    }

    /**
     * Download the VMX file of the VM, parse the VMX file of the template,
     * merge them and upload the resulting VMX on the machine.
     *
     * @param vm The VM that needs to be customized.
     * @param customization The VMX customization file associated with the
     * template.
     * @param templateName The name of the template associated with the VM.
     * @throws VirtException if the VM could not be customized
     */
    private void customizeVM(com.vmware.vim25.mo.VirtualMachine vm, File customization) throws VirtException {

        String vmName = vm.getName();
        Map<String, String> vmProperties = new HashMap<String, String>();
        File vmx = null;
        FileWriter fw = null;


        RuntimeContext.logger.log(Level.FINE, "Customizing {0}", vmName);
        try {
            //Get the VMX file of the VM
            String diskLocation = sshFileOperations.getFullPath(config.getDisksLocation());
            sshFileOperations.download(new File(diskLocation + "/" + vmName, vmName + ".vmx"), new File(System.getProperty("java.io.tmpdir")));

            //Parse the downloaded VMX file of the VM and store data in a Map
            vmx = new File(System.getProperty("java.io.tmpdir"), vmName + ".vmx");
            vmProperties = vmxToMap(vmx);

            //Parse the template VMX customization file and merge it with the map
            //containing the VMX file of the VM
            vmProperties = mergeTemplateVmxWithMap(vmProperties, customization.getAbsolutePath());

            //Write data of the map in the VMX file of the VM
            fw = new FileWriter(vmx.getAbsolutePath());
            Set< Entry<String, String>> data = vmProperties.entrySet();
            String dataString = "Using the following VMX file for the VM " + vmName + " : \n";
            for (Entry<String, String> entry : data) {
                dataString += entry.getKey() + " = \"" + entry.getValue() + "\"\n";
                fw.write(entry.getKey() + " = \"" + entry.getValue() + "\"\n");
            }
            fw.flush();
            RuntimeContext.logger.log(Level.FINE, dataString);

            //upload the resulting VMX on the machine to replace the old one.
            sshFileOperations.copy(vmx, new File(diskLocation + "/" + vmName));

        } catch (IOException ex) {
            if (vmx == null) {
                throw new VirtException("Could not download the original VMX file of " + vmName + ", customization failed", ex);
            } else {
                throw new VirtException("Could not write or upload the customized VMX file of " + vmName + ", customization failed", ex);
            }
        } catch (VirtException ex) {
            throw new VirtException("Could not customize " + vmName + " with the customization file "
                    + customization.getAbsolutePath(), ex);
        } finally {
            //delete temp file
            if (vmx != null && vmx.exists()) {
                vmx.delete();
            }
            try {
                if (fw != null) {
                    fw.close();
                }
            } catch (IOException ex) {
                RuntimeContext.logger.log(Level.FINEST, "Error while closing " + vmx.getAbsolutePath(), ex);
            }
        }
    }

    /**
     * Store the data of a VMX file into a map.
     *
     * @param vmx The file to store
     * @return A map containing the data of @parameter vmx.
     * @throws VirtException If the file could not be accessed or it is
     * malformed.
     */
    private Map<String, String> vmxToMap(File vmx) throws VirtException {

        Map result = new HashMap<String, String>();
        BufferedReader brVmx = null;
        InputStreamReader ipsrVmx = null;
        FileInputStream ipsVmx = null;

        try {
            //Parse the VMX file and store data in the Map
            ipsVmx = new FileInputStream(vmx);
            ipsrVmx = new InputStreamReader(ipsVmx);
            brVmx = new BufferedReader(ipsrVmx);
            String[] splitLineVmx;
            String lineVmx;
            while ((lineVmx = brVmx.readLine()) != null) {
                if (!lineVmx.startsWith("#") && lineVmx.length() > 0) { // ignore comments and empty lines
                    splitLineVmx = lineVmx.split("=");
                    if (splitLineVmx.length == 2) {
                        String key = splitLineVmx[0].trim().toLowerCase();
                        String value = splitLineVmx[1].trim().replace("\"", "");
                        result.put(key, value);
                    } else {
                        RuntimeContext.logger.log(Level.WARNING, "In file \"{0}\", line \"{1}\" is malformed and will be ignored ",
                                new Object[]{vmx.getAbsolutePath(), lineVmx});
                    }
                }
            }
            return result;
        } catch (IOException e) {
            throw new VirtException("Exception while parsing the VMX file : " + vmx.getAbsolutePath(), e);
        } finally {
            //close the streams
            try {
                if (brVmx != null) {
                    brVmx.close();
                }
                if (ipsrVmx != null) {
                    ipsrVmx.close();
                }
                if (ipsVmx != null) {
                    ipsVmx.close();
                }
            } catch (IOException ex) {
                RuntimeContext.logger.log(Level.FINEST, "Some streams might not have been closed while parsing file " + vmx.getAbsolutePath(), ex);
            }
        }

    }

    /**
     * Customize a map containing the data of a valid VMX file with the VMX
     * configuration file of a template. If a parameter appears in both the map
     * and the VMX configuration file, the value from the configuration file is
     * kept. Any device (floppy, ide ...) in the VMX configuration file is
     * ignored.
     *
     * @param map A map containing the data of a valid VMX file.
     * @param vmxPath Path to a VMX template configuration file
     * @return A map containing the merge of the @parameter map and the
     * @parameter vmxPath file.
     * @throws VirtException if the @parameter vmxPath file could not be read.
     */
    private Map<String, String> mergeTemplateVmxWithMap(Map<String, String> map, String vmxPath) throws VirtException {

        Map<String, String> result = new HashMap<String, String>();
        FileInputStream ipsCustomization = null;
        InputStreamReader ipsrCustomization = null;
        BufferedReader brCustomization = null;

        result.putAll(map);
        try {
            ipsCustomization = new FileInputStream(vmxPath);
            ipsrCustomization = new InputStreamReader(ipsCustomization);
            brCustomization = new BufferedReader(ipsrCustomization);
            String[] splitLineCustomization;
            String lineCustomization;
            while ((lineCustomization = brCustomization.readLine()) != null) {
                if (!lineCustomization.startsWith("#") && lineCustomization.length() > 0) { // ignore comments and empty lines
                    splitLineCustomization = lineCustomization.split("=");
                    String key = splitLineCustomization[0].trim().toLowerCase();
                    String value = splitLineCustomization[1].trim().replace("\"", "");
                    if (key.startsWith("scsi")) {
                        //ignoring scsi devices
                        RuntimeContext.logger.log(Level.WARNING, "SCSI device : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.startsWith("ide")) {
                        //ide is reserved for customization iso file 
                        RuntimeContext.logger.log(Level.WARNING, "IDE device : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.equalsIgnoreCase("uuid.bios")) {
                        //uuid is set by the plugin
                        RuntimeContext.logger.log(Level.WARNING, "UUID : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.startsWith("floppy")) {
                        //could not add a floppy disk
                        RuntimeContext.logger.log(Level.WARNING, "Floppy device : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.matches("ethernet.\\.address")) {
                        //cannot use mac adresses from template because all VM would have the same mac address 
                        RuntimeContext.logger.log(Level.WARNING, "MAC address : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.matches("ethernet.\\.generatedaddress")) {
                        //cannot use mac adresses from template because all VM would have the same mac address  
                        RuntimeContext.logger.log(Level.WARNING, "Generated address : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.matches("ethernet.\\.addresstype")) {
                        // addresstype already forced to "static"
                        RuntimeContext.logger.log(Level.WARNING, "MAC address type : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.matches("ethernet.\\.checkmacaddress")) {
                        // checkMACAddress already forced to "false"
                        RuntimeContext.logger.log(Level.WARNING, "Check MAC address : ignoring {0}={1} in {2} configuration file",
                                new Object[]{key, value, vmxPath});
                    } else if (key.matches("ethernet.\\.present") && value.equalsIgnoreCase("true")) {
                        //set mac address
                        String ethernetX = key.substring(0, "ethernetX.".length());
                        OsInterface os = services.forContract(OsInterface.class).get();
                        result.put(ethernetX + "present", "TRUE");
                        result.put(ethernetX + "addresstype", "static");
                        result.put(ethernetX + "address", os.macAddressGen());
                        result.put(ethernetX + "checkmacaddress", "FALSE");
                        result.put(ethernetX + "present", "true");
                    } else {
                        //unfiltered setting : we recopy it in our vmx
                        result.put(key, value);
                    }
                }

            }
            return result;

        } catch (IOException e) {
            throw new VirtException("Exception while parsing the template configuration file " + vmxPath, e);
        } finally {
            try {
                if (brCustomization != null) {
                    brCustomization.close();
                }
                if (ipsrCustomization != null) {
                    ipsrCustomization.close();
                }
                if (ipsCustomization != null) {
                    ipsCustomization.close();
                }
            } catch (IOException ex) {
                RuntimeContext.logger.log(Level.FINEST, "Some streams might not have been closed while parsing " + vmxPath, ex);
            }

        }


    }

    /**
     * Make a copy of the virtual disk of a template. This copy is renamed to
     * @parameter vmName and will be attached to the VM.
     *
     * @param vmName Name of the VM the copy of the virtual disk will be
     * attached to.
     * @param templateName Name of the template to duplicate.
     * @throws VirtException when virtual disk cannot be duplicated or renamed.
     */
    private void copyDisk(String vmName, String templateName) throws VirtException {
        try {
            //searching the VMDK disk among the template's files
            HostDatastoreBrowser hdb = hs.getDatastoreBrowser();
            HostDatastoreBrowserSearchSpec hdbss = new HostDatastoreBrowserSearchSpec();
            hdbss.setQuery(new FileQuery[]{new VmDiskFileQuery()});
            FileQueryFlags fqf = new FileQueryFlags();
            //fileOwner has to be set (true or false) because of a bug in VI Java
            fqf.setFileOwner(false);
            hdbss.setDetails(fqf);
            hdbss.setSearchCaseInsensitive(false);
            hdbss.setMatchPattern(new String[]{"*"});
            Task task = hdb.searchDatastoreSubFolders_Task(getTemplatePath(templateName), hdbss);
            String disk;
            try {
                task.waitForTask();
                ArrayOfHostDatastoreBrowserSearchResults searchResults =
                        (ArrayOfHostDatastoreBrowserSearchResults) task.getTaskInfo().getResult();
                HostDatastoreBrowserSearchResults[] results = searchResults.getHostDatastoreBrowserSearchResults();
                FileInfo[] fileInfo = results[0].getFile();
                disk = fileInfo[0].path;
            } catch (Exception ex) {
                throw (new IOException("Could not find any vmdk disk for template " + templateName + ", cannot create a VM without a working template"));
            }

            //making a copy of the template's virtual disk
            //using VirtualDiskManager (ESX only, not vCenter)
            VirtualDiskManager diskManager = si.getVirtualDiskManager();
            FileBackedVirtualDiskSpec vds = new FileBackedVirtualDiskSpec();
            vds.setAdapterType(VirtualDiskAdapterType.lsiLogic.name());
            vds.setDiskType(VirtualDiskType.thin.name());
            Task copyTask = diskManager.copyVirtualDisk_Task(getTemplatePath(templateName) + "/" + disk,
                    dc, getVMPath(vmName) + "/" + vmName + ".vmdk", null, vds, Boolean.TRUE);
            String result = copyTask.waitForTask();
            if (!result.equalsIgnoreCase(Task.SUCCESS)) {
                RuntimeContext.logger.log(Level.SEVERE, "Error while duplicating the template disk on " + getName() + " ");
                throw new VirtException();
            }


        } catch (Exception ex) {
            throw new VirtException(templateName + " virtual disk could not be duplicated on " + getName() + " ", ex);
        }
    }

    /**
     * Create an empty, unformatted and unpartitionned VMDK virtual disk of at
     * least 2Mb. The operation is failing if given capacity is inferior to
     * 2000Kb.
     *
     * @param name Name of the VMDK to create
     * @param capacity Capacity of the VMDK to create in Kb, must be superior to
     * 2000.
     * @return True if VMDK was created successfully
     */
    private boolean createVmdkFile(String name, long capacity) {
        try {
            sshFileOperations.mkdir(name.substring(0, name.lastIndexOf('/')));
            VirtualDiskManager diskManager = si.getVirtualDiskManager();
            FileBackedVirtualDiskSpec fbvspec = new FileBackedVirtualDiskSpec();
            fbvspec.setAdapterType(VirtualDiskAdapterType.lsiLogic.toString());
            fbvspec.setCapacityKb(capacity);
            fbvspec.setDiskType(VirtualDiskType.thick.toString());
            Task task = diskManager.createVirtualDisk_Task(name + ".vmdk", null, fbvspec);
            String result = task.waitForTask();
            return result.equals(Task.SUCCESS);
        } catch (IOException ex) {
            RuntimeContext.logger.log(Level.SEVERE, "Could not create VMDK disk on " + getName() + " ", ex);
            return false;
        } catch (InterruptedException ex) {
            RuntimeContext.logger.log(Level.SEVERE, "Creation of VMDK disk on " + getName() + " was interrupted ", ex);
            return false;
        }
    }

    @Override
    public String getIpAddress() {
        return ipAddress;
    }

    @Override
    public <T> T execute(MachineOperations<T> operations) throws IOException {
        return operations.run(sshFileOperations);

    }

    @Override
    public PhysicalServerPool getServerPool() {
        return serverPool;
    }

    public void ping() throws IOException, InterruptedException {
        SSHLauncher ssl = getSSH();
        ssl.pingConnection();
    }

    @Override
    public void sleep() throws IOException, InterruptedException {
        Task standby = hs.powerDownHostToStandBy(30, true);
        standby.waitForTask();
    }

    private VMWareVirtualization getVirtualizationConfig() {
        return (VMWareVirtualization) getServerPool().getConfig().getVirtualization();
    }

    @Override
    public boolean isUp() {

        if (State.READY.equals(getState())) {
            return true;
        }
        if (getIpAddress() == null) {
            return false;
        }

        try {
            ping();
        } catch (Exception e) {
            RuntimeContext.logger.log(Level.SEVERE, "Exception while pinging {0} : {1}", new Object[]{getName(), e.getMessage()});
            return false;
        }
        // the machine is alive, let's connect to it's virtualization implementation
        try {
            connection();
        } catch (VirtException e) {
            RuntimeContext.logger.log(Level.SEVERE, getName() + " is up but connection failed"
                    + " with the user " + getUser().getName(), e);
            return false;
        }
        return true;
    }

    @Override
    public VirtUser getUser() {
        if (config.getUser() != null) {
            return config.getUser();
        } else {
            return serverPool.getConfig().getUser();
        }
    }

    @Override
    protected String getUserHome() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            getSSH().runCommand("echo $HOME", baos);
        } catch (Exception e) {
            return "/home/" + getUser().getName();
        }
        String userHome = baos.toString();
        // horrible hack to remove trailing \n
        return userHome.substring(0, userHome.length() - 1);
    }

    /**
     * Establish SSH connection with the machine.
     *
     * @return SSH connection to the machine.
     */
    private SSHLauncher getSSH() {
        File home = new File(System.getProperty("user.home"));
        String keyFile = new File(home, ".ssh/id_dsa").getAbsolutePath();

        String password = getUser().getPassword();
        sshLauncher.init(getUser().getName(), ipAddress, 22, password, keyFile, null, Logger.getAnonymousLogger());
        return sshLauncher;
    }

    /**
     * Get the datastore path of a template
     *
     * @param templateName the name of the template
     * @return datastore path of the template
     */
    private String getTemplatePath(String templateName) {
        return sshFileOperations.getFullPath(config.getTemplatesLocation() + "/" + templateName + "/");
    }

    /**
     * Get the datastore path of a VM
     *
     * @param vmName the name of the VM
     * @return datastore path of the VM
     */
    private String getVMPath(String vmName) {
        return sshFileOperations.getFullPath(config.getDisksLocation() + "/" + vmName + "/");
    }
}
