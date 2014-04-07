for(Datacenter dc : dcList){
			ManagedEntity[] childEntitys=dc.getHostFolder().getChildEntity();
			for(int i=0;i<childEntitys.length;i++){
				if("ClusterComputeResource".equals(childEntitys[i].getMOR().type)){
					
					//集群
					ClusterComputeResource cr = (ClusterComputeResource)childEntitys[i];
					
					
					
				}
			}
			/*
			 * 遍历所有集群 
			 */
			for(ClusterComputeResource cluster : clusterList){
				ClusterComputeResource cr = cluster;
				logger.info("获取集群所在的数据中心==>"+cr.getParent().getParent().getMOR().getType());
				if(cr.getParent().getParent().getMOR().getType().equals("Datacenter")){
					countCluster++;
					Datacenter crInDc = (Datacenter)cr.getParent().getParent();
					TccClusterConfig tccClusterConfig = new TccClusterConfig();
					tccClusterConfig.setClusterName(cr.getName());
					tccClusterConfig.setDcname(crInDc.getName());
					tccClusterConfig.setOldFlag("0");//设置老资源标识符号
					tccClusterConfig.setVcenter("ZJ");
					tccClusterConfig.setVtype("VMware");
					tccClusterConfig.setVcpassword(vc.getVcenterPassword());
					tccClusterConfig.setVcip(vc.getVcenterIp());
					tccClusterConfig.setVcaccount(vc.getVcenterUser());
					HostSystem[] hostInClusters = cr.getHosts();
					for(HostSystem tempHost : hostInClusters){
						TccPhysiscResourceInfo tccPhysiscResourceInfo = new TccPhysiscResourceInfo();
						tccPhysiscResourceInfo.setPhysicsName(tempHost.getName());
						tccPhysiscResourceInfo.setPhysicsIp(tempHost.getName());
						tccPhysiscResourceInfo.setEnableFlg("1");
						tccPhysiscResourceInfo.setPhysicsBelong(Long.valueOf(1));
						tccPhysiscResourceInfo.setVirtualFlag("1");
						//tccPhysiscResourceInfo.setUuid(tempHost.getConfig);
						tccPhysiscResourceInfo.setOsVersionCd("471");
						tccPhysiscResourceInfo.setHardwareTypeCd("186");		
						tccPhysiscResourceInfo.setCpuCoreTotalCount(Long.valueOf(tempHost.getHardware().getCpuInfo().getNumCpuCores()));
						tccPhysiscResourceInfo.setCpuCoreRemainCount(Long.valueOf(tempHost.getHardware().getCpuInfo().getNumCpuCores()));
						tccPhysiscResourceInfo.setRamRemainSize(Long.valueOf(tempHost.getHardware().getCpuInfo().getNumCpuCores()));//todo
						tccPhysiscResourceInfo.setRamTotalSize(Long.valueOf(tempHost.getHardware().getCpuInfo().getNumCpuCores()));//todo
						tccPhysiscResourceInfo.setUsageFlag("01");
						tccPhysiscResourceInfo.setTccCluster(tccClusterConfig);
		
						
						VirtualMachine[] vmInHosts=tempHost.getVms();
						Set<TccApplyedHostResource> tccApplyedHostResourceSet = new HashSet<TccApplyedHostResource>();
						for(VirtualMachine tempVm : vmInHosts){
							TccApplyedHostResource  tccApplyedHostResource = new TccApplyedHostResource();
							tccApplyedHostResource.setHostNane(tempVm.getName());
							tccApplyedHostResource.setIpAddress(tempVm.getGuest().getIpAddress());
							tccApplyedHostResource.setUuid(tempVm.getGuest().getGuestId());		
							tccApplyedHostResource.setEnableFlg("1");
							tccApplyedHostResource.setCpuCoreCount(Long.valueOf(tempVm.getConfig().getHardware().getNumCPU()));
							tccApplyedHostResource.setRamSize(Long.valueOf(tempVm.getConfig().getHardware().getMemoryMB()));
							tccApplyedHostResource.setCrtDttm(new Date());
							tccApplyedHostResource.setCrtUserId(Long.valueOf(11111));
							tccApplyedHostResource.setHardwareTypeCd("186");
							tccApplyedHostResource.setHostLoginPassword("cpic");
							tccApplyedHostResource.setHostLoginUsername("cpic");
							tccApplyedHostResource.setHostSuperPassword("cpic");
							tccApplyedHostResource.setHostSuperUsername("cpic");
							tccApplyedHostResource.setRunStatusCd("1");
							tccApplyedHostResource.setTccPhysiscResourceInfo(tccPhysiscResourceInfo);
							tccApplyedHostResourceSet.add(tccApplyedHostResource);
							commonDao.save(tccApplyedHostResource);
						}
						
						//保存物理机
						tccPhysiscResourceInfo.setTccApplyedHostResources(tccApplyedHostResourceSet);
						tccPhysiscResourceInfoSet.add(tccPhysiscResourceInfo);
						commonDao.save(tccPhysiscResourceInfo);
					}
					
					tccClusterConfig.setTccPh(tccPhysiscResourceInfoSet);
					commonDao.save(tccClusterConfig);
			
				}
				
			}
			
		}
