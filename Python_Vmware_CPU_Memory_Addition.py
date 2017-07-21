#!/usr/bin/env python3
# VMware vSphere Python SDK
# Copyright (c) 2008-2013 VMware, Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Python program to add number of cpu's and memory to the vm's of the particular esxi host!
"""

import atexit
import argparse
import getpass

from pyVmomi import vim
from pyVim import connect
from pyVmomi import vmodl
from collections import Counter

esxi_host="IP of esxi host"
port = 443
user= "username vsphere client"
password="password of vsphere user account"

ipAddress = "ip's with comma separated values" # put in the IP you are interested in

cpu = None
memory= None

print "cspec.numCPUs = " + str(cpu)
print "cspec.memoryMB = " + str(memory)

def get_obj(content, vimtype, name):
    obj = None
    container = content.viewManager.CreateContainerView(
        content.rootFolder, vimtype, True)
    for c in container.view:
        if c.name == name:
            obj = c
            break
    return obj
def main():

    try:
        service_instance = connect.SmartConnect(host=esxi_host,
                                                user=user,
                                                pwd=password,
                                                port=int(port))

        atexit.register(connect.Disconnect, service_instance)


        split = ipAddress.split(',',1)
        for ipadd in split:
         print(ipadd)
         searchIndex = service_instance.RetrieveContent().searchIndex
         vms = set( searchIndex.FindAllByIp(ip=ipadd, vmSearch=True))
         if len(vms) == 0:
           print "No VMs with IP: %s" % ipAddress
         else:
            for vm in vms:
             print vm.name
             
             if (cpu and memory):
                 content = service_instance.RetrieveContent()
                 vm = get_obj(content, [vim.VirtualMachine], vm.name)
                 cspec = vim.vm.ConfigSpec()
                 cspec.numCPUs = cpu # if you want 4 cpus
                 cspec.memoryMB = memory # 1GB of memory
                 vm.Reconfigure(cspec)
                 print "Both cpu and memory"
             elif cpu:
                 cspec = vim.vm.ConfigSpec()
                 cspec.numCPUs = cpu # if you want 4 cpus
                 vm.Reconfigure(cspec)
                 print "cpu"
             elif memory:
                 cspec = vim.vm.ConfigSpec()
                 cspec.memoryMB = memory # 1GB of memory
                 vm.Reconfigure(cspec)
                 print "memory"
             else:
                 print "Give the input"

    except vmodl.MethodFault as error:
        print "Caught vmodl fault : " + error.msg
        return -1

    return 0

# Start program
if __name__ == "__main__":
    main()
