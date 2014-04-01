     HostDatastoreSystem hds = host.getHostDatastoreSystem();
     HostDatastoreBrowser hdb = host.getDatastoreBrowser();
     Datastore[] allDS = hdb.getDatastores();
     HostConfigInfo hostConfigInfo = host.getConfig();
     HostFileSystemVolumeInfo hostFSVolumeInfo = hostConfigInfo.getFileSystemVolume();
     HostFileSystemMountInfo[] hostFSMountInfo= hostFSVolumeInfo.getMountInfo();
     for (HostFileSystemMountInfo hfsmi : hostFSMountInfo) {
         HostFileSystemVolume hfsv = hfsmi.getVolume();
         if (hfsv.getType().equalsIgnoreCase("nfs")){
             String dsName = hfsv.getName();
             for(Datastore ds: allDS) {
                 DatastoreInfo di = ds.getInfo();
                 if (di.getName().equals(dsName)){
                     HostNasVolume nas = ((NasDatastoreInfo)di).getNas();
                     if (nas.getRemoteHost().equals(nfsServer) & nas.getRemotePath().equals(datastorePathOnNfsServer)) {
                         try {
                        } catch (HostConfigFault e) {
                             logger.error("ERROR : unmountNfsDatastore : Could not remove Datastore named: " + ds.getName() + " on " +  "host: " + hostname , e);
                             ret = 1;
                             return ret;
                        } catch (ResourceInUse e) {
                             logger.error("ERROR : unmountNfsDatastore : Could not remove Datastore named: " + ds.getName() + " on " +  "host: " + hostname , e);
                             ret = 1;
                             return ret;
                        } catch (NotFound e) {
                             logger.error("ERROR : unmountNfsDatastore : Could not remove Datastore named: " + ds.getName() + " on " +  "host: " + hostname , e);
                             ret = 1;
                             return ret;
                        } catch (RuntimeFault e) {
                             logger.error("ERROR : unmountNfsDatastore : Could not remove Datastore named: " + ds.getName() + " on " +  "host: " + hostname , e);
                             ret = 1;
                             return ret;
                        } catch (RemoteException e) {
                             logger.error("ERROR : unmountNfsDatastore : Could not remove Datastore named: " + ds.getName() + " on " +  "host: " + hostname , e);
                             ret = 1;
                             return ret;
                        }
                         return 0;
                     }
                 }
             }
         }
     }
     logger.error("ERROR : unmountNfsDatastore : Could not find Datastore exported by " + nfsServer + " on " +  "host: " + hostname);
     ret = 0;
     return ret;
    hds.removeDatastore(ds);
