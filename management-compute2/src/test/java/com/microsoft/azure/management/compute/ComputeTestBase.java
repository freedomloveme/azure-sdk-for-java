/**
 * Copyright Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.management.compute;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;


import com.microsoft.azure.management.compute.models.*;
import com.microsoft.azure.management.resources.models.LongRunningOperationResponse;
import com.microsoft.azure.management.resources.models.ResourceGroup;

import com.microsoft.azure.utility.AuthHelper;
import com.microsoft.azure.utility.ResourceContext;
import com.microsoft.azure.utility.VMHelper;
import com.microsoft.windowsazure.core.OperationResponse;
import com.microsoft.windowsazure.exception.ServiceException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpStatus;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.network.NetworkResourceProviderService;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.MockIntegrationTestBase;
import com.microsoft.windowsazure.core.ServiceClient;
import com.microsoft.windowsazure.core.pipeline.apache.ApacheConfigurationProperties;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementService;

import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;

import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementService;
import org.junit.*;

public abstract class ComputeTestBase extends MockIntegrationTestBase{
    //protected static final String m_location = "SouthEastAsia";
    protected static final String resourceGroupNamePrefix = "javatest";
    protected static String m_subId;
    protected static String rgName;
    protected static String m_location = "SouthEastAsia";

    protected static Configuration config;
    protected static ComputeManagementClient computeManagementClient;
    protected static StorageManagementClient storageManagementClient;
    protected static ResourceManagementClient resourceManagementClient;
    protected static NetworkResourceProviderClient networkResourceProviderClient;

    protected static Log log = LogFactory.getLog(ComputeTestBase.class);

    static {
        String region = System.getenv("region");
        if (region != null && !region.isEmpty()) {
            m_location = region.replace(" ", "");
        }
    }

    protected static void createComputeManagementClient() throws Exception {
        computeManagementClient = ComputeManagementService.create(config);
        computeManagementClient.setLongRunningOperationRetryTimeout(500);

        addClient((ServiceClient<?>) computeManagementClient, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                createComputeManagementClient();
                return null;
            }
        });
    }

    protected static void createStorageManagementClient() throws Exception {
        storageManagementClient = StorageManagementService.create(config);
        addClient((ServiceClient<?>) storageManagementClient, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                createStorageManagementClient();
                return null;
            }
        });
    }

    protected static void createNetworkManagementClient() throws Exception {
        networkResourceProviderClient = NetworkResourceProviderService.create(config);
        addClient((ServiceClient<?>) networkResourceProviderClient, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                createNetworkManagementClient();
                return null;
            }
        });
    }

    protected static void createResourceManagementClient() throws Exception {
        config.setProperty(ApacheConfigurationProperties.PROPERTY_RETRY_HANDLER, new DefaultHttpRequestRetryHandler());

        resourceManagementClient = ResourceManagementService.create(config);
        addClient((ServiceClient<?>) resourceManagementClient, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                createResourceManagementClient();
                return null;
            }
        });
    }

    public static Configuration createConfiguration() throws Exception {
        String baseUri = System.getenv("arm.url");
        if (IS_MOCKED) {
            return  ManagementConfiguration.configure(
                    null,
                    new URI(MOCK_URI),
                    MOCK_SUBSCRIPTION,
                    null);
        } else {
            return ManagementConfiguration.configure(
                    null,
                    baseUri != null ? new URI(baseUri) : null,
                    System.getenv(ManagementConfiguration.SUBSCRIPTION_ID),
                    AuthHelper.getAccessTokenFromServicePrincipalCredentials(
                            System.getenv(ManagementConfiguration.URI), System.getenv("arm.aad.url"),
                            System.getenv("arm.tenant"), System.getenv("arm.clientid"),
                            System.getenv("arm.clientkey")
                    ).getAccessToken());
        }
    }

    protected static void ensureClientsInitialized() throws Exception {
        config = createConfiguration();
        createResourceManagementClient();
        createComputeManagementClient();
        createStorageManagementClient();
        createNetworkManagementClient();

        // m_subId = computeManagementClient.getCredentials().getSubscriptionId();
        m_subId = System.getenv("management.subscription.id");
        rgName = resourceGroupNamePrefix + randomString(5);

        addRegexRule(resourceGroupNamePrefix + "[a-z]{5}");
        addRegexRule("https://management.azure.com", MOCK_URI);
        log.info("Region: " + m_location + " ; Using rgname: " + rgName);
    }

    protected static String randomString(int length) {
        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder(length);
        for (int i=0; i<length; i++) {
            stringBuilder.append((char)('a' + random.nextInt(26)));
        }
        return stringBuilder.toString();
    }

    protected static void createOrUpdateResourceGroup(String rgName) throws ServiceException, IOException, URISyntaxException {
        resourceManagementClient.getResourceGroupsOperations().createOrUpdate(rgName,
                new ResourceGroup(m_location));
    }

    protected static VirtualMachine createVM(ResourceContext context, String vmName)
            throws Exception {
        return createVM(context, vmName, "", false, null);
    }

    protected static VirtualMachine createVM(
            ResourceContext context, String vmName, Consumer<VirtualMachine> vmInputModifier)
            throws Exception {
        return createVM(context, vmName, "", false, vmInputModifier);
    }

    protected static VirtualMachine createVM(
            ResourceContext context,
            String vmName, String imRefId, boolean createWithPublicIpAddr, Consumer<VirtualMachine> vmInputModifier)
            throws Exception {

        log.info(String.format("Create vm: %s, rg: %s, imRef: %s.",
                vmName, rgName, imRefId));

        if (context == null) {
            context = new ResourceContext(
                    m_location, rgName, m_subId, createWithPublicIpAddr);
        }

        if (imRefId != null && !imRefId.isEmpty()) {
            context.setSourceImageReferenceUri(imRefId);
        }

        VirtualMachineCreateOrUpdateResponse vmResponse;
        try {
            vmResponse = VMHelper.createVM(
                    resourceManagementClient, computeManagementClient, networkResourceProviderClient, storageManagementClient,
                    context, vmName, "Foo12", "BaR@123rgababaab", vmInputModifier);

        } catch (Exception ex) {
            log.info(ex.toString());
            throw ex;
        }

        log.info(vmResponse.getVirtualMachine().getName() + " creation is requested.");
        // String expectedVMRefId = ComputeTestHelper.getVMReferenceId(m_subId, rgName, vmName);

        Assert.assertEquals(HttpStatus.SC_CREATED, vmResponse.getStatusCode());
        Assert.assertEquals(vmName, vmResponse.getVirtualMachine().getName());
        Assert.assertEquals(
                context.getVMInput().getLocation().toLowerCase(),
                vmResponse.getVirtualMachine().getLocation().toLowerCase());
        Assert.assertEquals(
                context.getAvailabilitySetId().toLowerCase(),
                vmResponse.getVirtualMachine().getAvailabilitySetReference().getReferenceUri().toLowerCase());
        validateVM(context.getVMInput(), vmResponse.getVirtualMachine());

        //wait for the vm creation
        ComputeLongRunningOperationResponse lroResponse = computeManagementClient.getLongRunningOperationStatus(
                vmResponse.getAzureAsyncOperation());
        validateLROResponse(lroResponse, vmResponse.getAzureAsyncOperation());

        //validate get vm
        log.info("get vm");
        VirtualMachineGetResponse getVMResponse = computeManagementClient.getVirtualMachinesOperations()
                .get(rgName, vmName);
        Assert.assertEquals(HttpStatus.SC_OK, getVMResponse.getStatusCode());
        validateVM(context.getVMInput(), getVMResponse.getVirtualMachine());
        return getVMResponse.getVirtualMachine();
    }

    protected static void validateVM(VirtualMachine vmInput, VirtualMachine vmOut) {
        Assert.assertTrue(vmOut.getProvisioningState() != null && !vmOut.getProvisioningState().isEmpty());
        Assert.assertEquals(
                vmInput.getHardwareProfile().getVirtualMachineSize(), vmOut.getHardwareProfile().getVirtualMachineSize());
        Assert.assertTrue(vmOut.getStorageProfile().getOSDisk() != null);
        Assert.assertNotNull(vmOut.getAvailabilitySetReference());
        Assert.assertEquals(
                vmInput.getAvailabilitySetReference().getReferenceUri().toLowerCase(),
                vmOut.getAvailabilitySetReference().getReferenceUri().toLowerCase());

        if (vmInput.getStorageProfile().getOSDisk() != null) {
            Assert.assertEquals(
                    vmInput.getStorageProfile().getOSDisk().getName(), vmOut.getStorageProfile().getOSDisk().getName());
            Assert.assertEquals(
                    vmInput.getStorageProfile().getOSDisk().getVirtualHardDisk().getUri(),
                    vmOut.getStorageProfile().getOSDisk().getVirtualHardDisk().getUri());
            Assert.assertEquals(
                    vmInput.getStorageProfile().getOSDisk().getCaching(),
                    vmOut.getStorageProfile().getOSDisk().getCaching());
        }

        if (vmInput.getStorageProfile().getDataDisks() != null
                && !vmInput.getStorageProfile().getDataDisks().isEmpty()) {
            for (DataDisk diskInput : vmInput.getStorageProfile().getDataDisks()) {
                DataDisk diskOut = null;
                for (DataDisk tmpDisk : vmOut.getStorageProfile().getDataDisks()) {
                    if (tmpDisk.getName().equals(diskInput.getName())) {
                        diskOut = tmpDisk;
                    }
                }

                Assert.assertNotNull(diskOut);
                Assert.assertNotNull(diskOut.getVirtualHardDisk());
                Assert.assertNotNull(diskOut.getVirtualHardDisk().getUri());

                if (diskInput.getSourceImage() != null && diskInput.getSourceImage().getUri() != null) {
                    Assert.assertNotNull(diskOut.getSourceImage());
                    Assert.assertEquals(diskInput.getSourceImage().getUri(), diskOut.getSourceImage().getUri());
                }
            }
        }

//        if (vmInput.getOSProfile() != null
//                && vmInput.getOSProfile().getSecrets() != null
//                && !vmInput.getOSProfile().getSecrets().isEmpty()) {
//            for (VaultSecretGroup secret : vmInput.getOSProfile().getSecrets()) {
//                VaultSecretGroup secretOut = null;
//                for (VaultSecretGroup tmpSecret : vmOut.getOSProfile().getSecrets()) {
//                    if (tmpSecret.getSourceVault().getReferenceUri() == secret.getSourceVault().getReferenceUri()) {
//                        secretOut = tmpSecret;
//                    }
//                }
//                //TODO cert validator
//            }
//        }

        validatePlan(vmInput.getPlan(), vmOut.getPlan());
    }

    protected static void validatePlan(Plan planInput, Plan planOut) {
        if (planInput == null || planOut == null) {
            Assert.assertEquals(planInput, planOut);
            return;
        }

        Assert.assertEquals(planInput.getName(), planOut.getName());
        Assert.assertEquals(planInput.getPublisher(), planOut.getPublisher());
        Assert.assertEquals(planInput.getProduct(), planOut.getProduct());
        Assert.assertEquals(planInput.getPromotionCode(), planOut.getPromotionCode());
    }

    protected static void validateVMInstanceView(VirtualMachine vmIn, VirtualMachine vmOut) {
        Assert.assertNotNull(vmOut.getInstanceView());
        VirtualMachineInstanceView instanceView = vmOut.getInstanceView();
        //check instance view status
        Assert.assertTrue(validateVMInstanceStatus(instanceView.getStatuses()));

        Assert.assertNotNull(instanceView.getDisks());
        Assert.assertTrue(instanceView.getDisks().size() > 0);

        if (vmIn.getStorageProfile().getOSDisk() != null) {
            Assert.assertTrue(
                    validateVMInstanceOSDisk(instanceView.getDisks(), vmIn.getStorageProfile().getOSDisk().getName()));
        }

        DiskInstanceView diskInstanceView = instanceView.getDisks().get(0);
        Assert.assertNotNull(diskInstanceView);
        Assert.assertNotNull(diskInstanceView.getStatuses().get(0).getDisplayStatus());
        Assert.assertNotNull(diskInstanceView.getStatuses().get(0).getCode());
        Assert.assertNotNull(diskInstanceView.getStatuses().get(0).getLevel());
    }

    protected static void validateLROResponse(ComputeLongRunningOperationResponse lroResponse, String operation) {
        String[] operationSegments = operation.split("/");
        String lastSeg = operationSegments[operationSegments.length - 1];
        String operationId = lastSeg.substring(0, lastSeg.indexOf('?'));

        Assert.assertNotNull(lroResponse);
        Assert.assertNotNull(lroResponse.getStatus());
        Assert.assertNotNull(lroResponse.getStartTime());
        Assert.assertEquals(operationId, lroResponse.getTrackingOperationId());
    }

    protected static void cleanupResourceGroup() throws Exception {
        if (!IS_MOCKED) {
            log.info("Start Remove rg: " + rgName);
            LongRunningOperationResponse deleteResponse = resourceManagementClient.getResourceGroupsOperations()
                    .beginDeleting(rgName);
            Assert.assertEquals(
                    "BeginDeleting status was not Accepted.", HttpStatus.SC_ACCEPTED, deleteResponse.getStatusCode());
            log.info("Remove rg request submitted.");
        }
    }

    protected static String generateName(String prefix) {
        String name = resourceGroupNamePrefix + prefix + randomString(5);
        addRegexRule(resourceGroupNamePrefix + prefix + "[a-z]{5}");
        return name;
    }

    private static boolean validateVMInstanceStatus(ArrayList<InstanceViewStatus> statusList) {
        for (InstanceViewStatus s : statusList) {
            if (s != null && s.getCode() != null && !s.getCode().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean validateVMInstanceOSDisk(ArrayList<DiskInstanceView> diskList, String osDiskName) {
        for (DiskInstanceView d : diskList) {
            if (d.getName().equals(osDiskName)) {
                return true;
            }
        }
        return false;
    }
}