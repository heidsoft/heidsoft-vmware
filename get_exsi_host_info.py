# -*- coding: utf-8 -*-
#!/usr/bin/python
"""
author: heidsoft
Github: https://github.com/heidsoft
Description：get esxi host info
"""
import atexit
from pyVmomi import vim
from pyVim.connect import SmartConnectNoSSL, Disconnect
import time
import datetime
from pyVmomi import vmodl
from threading import Thread
import traceback


class perfdata():
   def metricvalue(self,item,depth):
      maxdepth=10
      if hasattr(item, 'childEntity'):
         if depth > maxdepth:
             return 0
         else:
             item = item.childEntity
             item=self.metricvalue(item,depth+1)
      return item

   def run(self,content,vihost):
       output=[]
       try:
          perf_dict = {}
          perfManager = content.perfManager
          perfList = content.perfManager.perfCounter
          esxi_host_metric_file = open("esxi_host_metric_file.txt","wb")
          for counter in perfList: #build the vcenter counters for the objects
              counter_full = "{}.{}.{}".format(counter.groupInfo.key,counter.nameInfo.key,counter.rollupType)
              # print counter.unitInfo.label
              # print counter.unitInfo.summary
              # print counter.unitInfo.key
              perf_dict[counter_full] = counter.key
          
          #esxi_host_metric_file.close()

          esxi_host_metric_list = [
            'cpu.usage.maximum',
            'cpu.usage.minimum',
            'cpu.ready.summation',
            'cpu.capacity.contention.average',
            'mem.usage.minimum',
            'mem.usage.maximum',
            'disk.usage.minimum',
            'disk.usage.maximum',
            'net.usage.minimum',
            'net.usage.maximum'
          ]
          
          for counter_name in esxi_host_metric_list:
            print counter_name
            counterId = perf_dict[counter_name]
            metricId = vim.PerformanceManager.MetricId(counterId=counterId, instance="")

            timenow=datetime.datetime.now()
            #指标数据查询，开始时间
            startTime = timenow - datetime.timedelta(days=1)

            #结束时间
            endTime = timenow
            search_index = content.searchIndex
            host = search_index.FindByDnsName(dnsName=vihost, vmSearch=False)
            query = vim.PerformanceManager.QuerySpec(entity=host,metricId=[metricId],intervalId=20,startTime=startTime,endTime=endTime)
            stats=perfManager.QueryPerf(querySpec=[query])
           
            count=0
            if stats is not None and len(stats):
              for val in stats[0].value[0].value:
                  perfinfo={}
                  val=float(val/100)
                  perfinfo['timestamp']=stats[0].sampleInfo[count].timestamp
                  perfinfo['hostname']=vihost
                  perfinfo['metric']=counter_name
                  perfinfo['value']=val
                  output.append(perfinfo)
                  count+=1
            
            for out in output:
                esxi_info = "Hostname: {}  TimeStame: {} Metric: {} Usage: {}".format (out['hostname'],out['timestamp'],out['metric'],out['value'])
                esxi_host_metric_file.write(esxi_info+"\n")
                print esxi_info

            esxi_host_metric_file.close()
         
       except vmodl.MethodFault as e:
           traceback.print_exc()
           print("Caught vmodl fault : " + e.msg)
           return 0
       except Exception as e:
           traceback.print_exc()
           print("Caught exception : " + str(e))
           return 0

def main():
   user='administrator@oneoaas.com'
   passwd='Oneoaas#123'
   port=443
   vc='10.0.2.8'
   try:
       si = SmartConnectNoSSL(
               host=vc,
               user=user,
               pwd=passwd,
               port=port)
   except:
       print "连接失败"
   atexit.register(Disconnect, si)

   content = si.RetrieveContent()
   perf=perfdata()
   for child in content.rootFolder.childEntity:
        datacenter=child
        hostfolder = datacenter.hostFolder
        hostlist=perf.metricvalue(hostfolder,0)
        for hosts in hostlist:
            esxhosts=hosts.host
            for esx in esxhosts:
                summary=esx.summary
                esxname=summary.config.name
                p = Thread(target=perf.run, args=(content,esxname,))
                p.start()


if __name__ == "__main__":
    main()

