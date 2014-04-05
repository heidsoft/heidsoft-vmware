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

import com.trilead.ssh2.SCPClient;
import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import org.glassfish.cluster.ssh.launcher.SSHLauncher;
import org.glassfish.virtualization.spi.FileOperations;
import org.glassfish.virtualization.util.RuntimeContext;

/**
 * {@link FileOperations} implementation using VI Java and SCP.
 *
 * @author  Fabien Leroy - fabien.leroy@serli.com
 */
final class VMWareFileOperations implements FileOperations {

    Datacenter dc;
    FileManager fileManager;
    HostSystem hs;
    final SSHLauncher sshLauncher;
    final VMWareMachine machine;
    final ServiceInstance si;
    final ThreadLocal<ClientTuple> sftpClientThreadLocal = new ThreadLocal<ClientTuple>();

    private static final class ClientTuple {

        final SCPClient scpClient;

        private ClientTuple(SCPClient scpClient) {
            this.scpClient = scpClient;
        }
    }

    VMWareFileOperations(VMWareMachine machine, SSHLauncher sshLauncher, ServiceInstance si, Datacenter dc,
            HostSystem hs) throws IOException {
        this.sshLauncher = sshLauncher;
        this.machine = machine;
        this.si = si;
        this.dc = dc;
        this.hs = hs;
        fileManager = si.getFileManager();
        if (fileManager == null) {
            throw new IOException("Exception while retrieving the File Manager for " + machine.getName() + ", connection failed");
        }

    }

    private synchronized SCPClient getSCPClient() throws IOException {

        ClientTuple clients = sftpClientThreadLocal.get();
        if (clients == null) {
            clients = init();
            sftpClientThreadLocal.set(clients);
        }

        return clients.scpClient;
    }

    private ClientTuple init() throws IOException {
        return new ClientTuple(sshLauncher.getSCPClient());
    }

    @Override
    public synchronized boolean mkdir(String destPath) throws IOException {
        destPath = getFullPath(destPath);
        if (!exists(destPath)) {
            RuntimeContext.logger.log(Level.FINEST, "Creating directory : {0}", destPath);
            fileManager.makeDirectory(destPath, dc, true);
            return true;
        }
        RuntimeContext.logger.log(Level.FINEST, "Directory : {0} could not be created because it already exists", destPath);
        return false;
    }

    @Override
    public synchronized boolean delete(String path) throws IOException {
        path = getFullPath(path);
        if (exists(path)) {
            Task deletion = fileManager.deleteDatastoreFile_Task(path, dc);
            String result;
            try {
                result = deletion.waitForTask();
                return result.equals(Task.SUCCESS);
            } catch (Exception ex) {
                RuntimeContext.logger.log(Level.SEVERE, "Could not delete " + path + " ", ex);
            }
        }
        RuntimeContext.logger.log(Level.FINEST, "Could not delete {0} because it does not exist", path);
        return false;
    }

    @Override
    public synchronized boolean mv(String source, String dest) throws IOException {
        //dest = path/fileName and not path only
        //allows to rename a file
        String sourcePath = getFullPath(source);
        String destPath = getFullPath(dest);

        if (exists(dest)) {
            delete(dest);
        }
        Task move = fileManager.moveDatastoreFile_Task(sourcePath, dc, destPath, dc, true);
        try {
            return move.waitForTask().equals(Task.SUCCESS);
        } catch (Exception ex) {
            RuntimeContext.logger.log(Level.SEVERE, "Could not move " + source + " to " + dest + " ", ex);
            return false;
        }
    }

    @Override
    public synchronized long length(String path) throws IOException {
        path = getFullPath(path);
        String dsPath = path;
        String[] splitPath = dsPath.split("/");
        String fileName = splitPath[splitPath.length - 1];
        String filePath = path.substring(0, path.length() - fileName.length());

        HostDatastoreBrowser hdb = hs.getDatastoreBrowser();
        HostDatastoreBrowserSearchSpec searchSpec = new HostDatastoreBrowserSearchSpec();
        searchSpec.setMatchPattern(new String[]{fileName});
        FileQueryFlags queryFlags = new FileQueryFlags();
        //fileOwner has to be set (true or false) because of a bug in VI Java
        queryFlags.setFileOwner(false);
        queryFlags.setFileSize(true);
        searchSpec.setDetails(queryFlags);
        Task task = hdb.searchDatastore_Task(filePath, searchSpec);
        try {
            task.waitForTask();
        } catch (Exception ex) {
            throw (new IOException("Could not search file " + path + " ", ex));
        }
        HostDatastoreBrowserSearchResults searchResults =
                (HostDatastoreBrowserSearchResults) task.getTaskInfo().getResult();
        FileInfo[] fileInfo = searchResults.getFile();
        if (fileInfo == null || fileInfo.length == 0) {
            throw (new IOException("File not found " + path));
        }
        return fileInfo[0].fileSize;
    }

    @Override
    public synchronized boolean exists(String path) throws IOException {
        //works for both files and folders

        path = getFullPath(path);
        HostDatastoreBrowser hdb = hs.getDatastoreBrowser();

        String[] splitPath = path.split("/");
        String fileName = splitPath[splitPath.length - 1];
        String folder = path.substring(0, path.length() - fileName.length());
        HostDatastoreBrowserSearchSpec fileSearchSpec = new HostDatastoreBrowserSearchSpec();
        fileSearchSpec.setMatchPattern(new String[]{fileName});

        try {
            Task task = hdb.searchDatastore_Task(folder, fileSearchSpec);
            task.waitForTask();
            HostDatastoreBrowserSearchResults searchResults =
                    (HostDatastoreBrowserSearchResults) task.getTaskInfo().getResult();
            if (searchResults == null) {
                return false;
            }
            FileInfo[] fileInfo = searchResults.getFile();
            return (fileInfo != null && fileInfo.length > 0);
        } catch (com.vmware.vim25.FileNotFound ex) {
            //normal case
        } catch (Exception ex) {
            RuntimeContext.logger.log(Level.SEVERE, "Exception while testing if " + path + " exists ", ex);
        }

        return false;
    }

    @Override
    public Date mod(String path) throws IOException {
        path = getFullPath(path);

        String[] splitPath = path.split("/");
        String fileName = splitPath[splitPath.length - 1];
        String filePath = path.substring(0, path.length() - fileName.length());

        HostDatastoreBrowser hdb = hs.getDatastoreBrowser();
        HostDatastoreBrowserSearchSpec searchSpec = new HostDatastoreBrowserSearchSpec();
        searchSpec.setMatchPattern(new String[]{fileName});
        FileQueryFlags queryFlags = new FileQueryFlags();
        //fileOwner has to be set (true or false) because of a bug in VI Java
        queryFlags.setFileOwner(false);
        queryFlags.setModification(true);
        searchSpec.setDetails(queryFlags);
        Task task = hdb.searchDatastore_Task(filePath, searchSpec);
        try {
            task.waitForTask();
        } catch (Exception ex) {
            throw (new IOException("Could not search file " + path + " ", ex));
        }
        HostDatastoreBrowserSearchResults searchResults =
                (HostDatastoreBrowserSearchResults) task.getTaskInfo().getResult();
        FileInfo[] fileInfo = searchResults.getFile();
        if (fileInfo == null || fileInfo.length == 0) {
            throw (new IOException("File not found " + path));
        }
        return fileInfo[0].modification.getTime();
    }

    @Override
    public synchronized void copy(File source, File destination) throws IOException {


        String destPath = destination + "/" + source.getName();

        if (!exists(destination.getPath())) {
            //if destination directory does not exist, create it
            mkdir(destination.getPath());
        } else if (exists(destPath)) {
            //if a file with the same name already exists in 
            //destination directory, delete it
            delete(destPath);
        }
        String localFile = source.getAbsolutePath();
        String remoteFile = getFullPath(destination.getPath());
        String datastoreName = extractDatastoreNameFromPath(remoteFile);
        Datastore ds = getDatastore(datastoreName);
        remoteFile = ds.getInfo().getUrl() + "/" + remoteFile.substring(("["+datastoreName+"] ").length());
        RuntimeContext.logger.log(Level.FINEST, "Uploading {0} to {1}", new Object[]{localFile, remoteFile});
        getSCPClient().put(localFile, remoteFile);
    }

    @Override
    public synchronized void localCopy(String source, String destDir) throws IOException {
        // destDir = folder only, not "folder/fileName"
        String sourcePath = getFullPath(source);
        String destPath = getFullPath(destDir);

        String[] splitSource = sourcePath.split("/");
        String fileName = splitSource[splitSource.length - 1];
        String dest = destPath.endsWith("/") ? destPath + fileName : destPath + "/" + fileName;

        if (exists(dest) && !dest.equals(sourcePath)) {
            delete(dest);
        }
        RuntimeContext.logger.log(Level.FINEST, "Copying {0} to {1}", new Object[]{sourcePath, dest});
        Task copy = fileManager.copyDatastoreFile_Task(sourcePath, dc, dest, dc, true);
        try {
            copy.waitForTask();
        } catch (Exception ex) {
            throw new IOException("Could not copy " + source + " to " + destDir + " ", ex);
        }

    }

    /**
     * Download the source file (on the VMwareMachine) to destination (on the DAS).
     * @param source File to be downloaded.
     * @param destination Destination File of the download.
     * @throws IOException if the operation failed
     */
    public synchronized void download(File source, File destination) throws IOException {
        String localFile = destination.getAbsolutePath();
        String remoteFile = getFullPath(source.getPath());
        SCPClient scpclient = sshLauncher.getSCPClient();
        String datastoreName = extractDatastoreNameFromPath(remoteFile);
        Datastore ds = getDatastore(datastoreName);
        remoteFile = ds.getInfo().getUrl() + "/" + remoteFile.substring(("["+datastoreName+"] ").length());
        RuntimeContext.logger.log(Level.FINEST, "Downloading {0} to {1}", new Object[]{remoteFile, localFile});
        scpclient.get(remoteFile, localFile);
    }

    @Override
    public List<String> ls(String path) throws IOException {

        path = getFullPath(path);
        List<String> filePaths = new ArrayList<String>();

        HostDatastoreBrowser hdb = hs.getDatastoreBrowser();
        HostDatastoreBrowserSearchSpec hdbss = new HostDatastoreBrowserSearchSpec();
        hdbss.setQuery(new FileQuery[]{new FileQuery()});
        FileQueryFlags fqf = new FileQueryFlags();
        //fileOwner has to be set (true or false) because of a bug in VI Java
        fqf.setFileOwner(false);
        hdbss.setDetails(fqf);
        hdbss.setSearchCaseInsensitive(false);
        hdbss.setMatchPattern(new String[]{"*"});
        Task task = hdb.searchDatastoreSubFolders_Task(path, hdbss);
        try {
            task.waitForTask();
            ArrayOfHostDatastoreBrowserSearchResults searchResults =
                    (ArrayOfHostDatastoreBrowserSearchResults) task.getTaskInfo().getResult();
            HostDatastoreBrowserSearchResults[] results = searchResults.getHostDatastoreBrowserSearchResults();
            if (results != null && results.length > 0) {
                FileInfo[] fileInfo = results[0].getFile();
                if (fileInfo != null) {
                    for (FileInfo fi : fileInfo) {
                        filePaths.add(fi.path);
                    }
                }
            }
        } catch (Exception e) {
            RuntimeContext.logger.log(Level.WARNING, "Exception while listing content of " + path, e);
        }

        return filePaths;
    }
    
    /**
     * Complete a path with the default datastore name if needed
     * @param path Path to be completed
     * @return A well formed path of the form "[datastoreName] /path/in/datastore"
     */
    public String getFullPath(String path) {
        if(!path.startsWith("["))
            return "[" + getDefaultDatastore().getInfo().getName() + "] " + path;
        return path;
    }
    
    /**
     * Get the list of datastores available on the machine
     * and returns the first one.
     * @return One datastore availble on the machine
     */
    public Datastore getDefaultDatastore() {
        return getDatastore(null);
    }
    
    /**
     * Search for a datastore
     * @param datastoreName The name of the datastore we are looking for.
     * @return The searched datastore
     */
    public Datastore getDatastore(String datastoreName) {
        Datastore[] datastores = dc.getDatastores();
        if (datastores.length == 0) {
            return null;
        }
        if (datastoreName == null ||datastoreName.length() == 0) {
            return datastores[0];
        } else {
            for (Datastore ds : datastores) {
                if (ds.getInfo().getName().equalsIgnoreCase(datastoreName)) {
                    return ds;
                }
            }
        }

        return null;
    }
    
    public String extractDatastoreNameFromPath(String path) {
        return path.substring(path.indexOf("[")+1, path.indexOf("]"));
    }
    
}

