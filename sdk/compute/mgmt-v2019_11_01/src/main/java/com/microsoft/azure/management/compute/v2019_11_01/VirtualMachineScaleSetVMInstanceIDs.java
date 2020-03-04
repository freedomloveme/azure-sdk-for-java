/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 *
 * Code generated by Microsoft (R) AutoRest Code Generator.
 */

package com.microsoft.azure.management.compute.v2019_11_01;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Specifies a list of virtual machine instance IDs from the VM scale set.
 */
public class VirtualMachineScaleSetVMInstanceIDs {
    /**
     * The virtual machine scale set instance ids. Omitting the virtual machine
     * scale set instance ids will result in the operation being performed on
     * all virtual machines in the virtual machine scale set.
     */
    @JsonProperty(value = "instanceIds")
    private List<String> instanceIds;

    /**
     * Get the virtual machine scale set instance ids. Omitting the virtual machine scale set instance ids will result in the operation being performed on all virtual machines in the virtual machine scale set.
     *
     * @return the instanceIds value
     */
    public List<String> instanceIds() {
        return this.instanceIds;
    }

    /**
     * Set the virtual machine scale set instance ids. Omitting the virtual machine scale set instance ids will result in the operation being performed on all virtual machines in the virtual machine scale set.
     *
     * @param instanceIds the instanceIds value to set
     * @return the VirtualMachineScaleSetVMInstanceIDs object itself.
     */
    public VirtualMachineScaleSetVMInstanceIDs withInstanceIds(List<String> instanceIds) {
        this.instanceIds = instanceIds;
        return this;
    }

}