/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.admin.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.StringConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import lombok.Getter;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.internal.FunctionsImpl;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.functions.api.utils.IdentityFunction;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;
import org.apache.pulsar.functions.proto.Function.ProcessingGuarantees;
import org.apache.pulsar.functions.proto.Function.Resources;
import org.apache.pulsar.functions.proto.Function.SinkSpec;
import org.apache.pulsar.functions.proto.Function.SourceSpec;
import org.apache.pulsar.functions.utils.FunctionConfig;
import org.apache.pulsar.functions.utils.Reflections;
import org.apache.pulsar.functions.utils.SinkConfig;
import org.apache.pulsar.functions.utils.Utils;
import org.apache.pulsar.io.core.Sink;

import net.jodah.typetools.TypeResolver;

import static org.apache.pulsar.common.naming.TopicName.DEFAULT_NAMESPACE;
import static org.apache.pulsar.common.naming.TopicName.PUBLIC_TENANT;

@Getter
@Parameters(commandDescription = "Interface for managing Pulsar Sinks (Egress data from Pulsar)")
public class CmdSinks extends CmdBase {

    private final CreateSink createSink;
    private final UpdateSink updateSink;
    private final DeleteSink deleteSink;
    private final LocalSinkRunner localSinkRunner;

    public CmdSinks(PulsarAdmin admin) {
        super("sink", admin);
        createSink = new CreateSink();
        updateSink = new UpdateSink();
        deleteSink = new DeleteSink();
        localSinkRunner = new LocalSinkRunner();

        jcommander.addCommand("create", createSink);
        jcommander.addCommand("update", updateSink);
        jcommander.addCommand("delete", deleteSink);
        jcommander.addCommand("localrun", localSinkRunner);
    }

    /**
     * Base command
     */
    @Getter
    abstract class BaseCommand extends CliCommand {
        @Override
        void run() throws Exception {
            processArguments();
            runCmd();
        }

        void processArguments() throws Exception {
        }

        abstract void runCmd() throws Exception;
    }

    @Parameters(commandDescription = "Run the Pulsar sink locally (rather than deploying it to the Pulsar cluster)")
    class LocalSinkRunner extends CreateSink {

        @Parameter(names = "--brokerServiceUrl", description = "The URL for the Pulsar broker")
        protected String brokerServiceUrl;

        @Override
        void runCmd() throws Exception {
            CmdFunctions.startLocalRun(createSinkConfigProto2(sinkConfig),
                    sinkConfig.getParallelism(), brokerServiceUrl, jarFile, admin);
        }
    }

    @Parameters(commandDescription = "Create Pulsar sink connectors")
    class CreateSink extends SinkCommand {
        @Override
        void runCmd() throws Exception {
            if (!areAllRequiredFieldsPresentForSink(sinkConfig)) {
                throw new RuntimeException("Missing arguments");
            }
            admin.functions().createFunction(createSinkConfig(sinkConfig), jarFile);
            print("Created successfully");
        }
    }

    @Parameters(commandDescription = "Update Pulsar sink connectors")
    class UpdateSink extends SinkCommand {
        @Override
        void runCmd() throws Exception {
            if (!areAllRequiredFieldsPresentForSink(sinkConfig)) {
                throw new RuntimeException("Missing arguments");
            }
            admin.functions().updateFunction(createSinkConfig(sinkConfig), jarFile);
            print("Updated successfully");
        }
    }

    @Parameters(commandDescription = "Create Pulsar sink connectors")
    abstract class SinkCommand extends BaseCommand {
        @Parameter(names = "--tenant", description = "The sink's tenant")
        protected String tenant;
        @Parameter(names = "--namespace", description = "The sink's namespace")
        protected String namespace;
        @Parameter(names = "--name", description = "The sink's name")
        protected String name;
        @Parameter(names = "--className", description = "The sink's class name")
        protected String className;
        @Parameter(names = "--inputs", description = "The sink's input topic or topics (multiple topics can be specified as a comma-separated list)")
        protected String inputs;
        @Parameter(names = "--customSerdeInputs", description = "The map of input topics to SerDe class names (as a JSON string)")
        protected String customSerdeInputString;
        @Parameter(names = "--processingGuarantees", description = "The processing guarantees (aka delivery semantics) applied to the Sink")
        protected FunctionConfig.ProcessingGuarantees processingGuarantees;
        @Parameter(names = "--parallelism", description = "The sink's parallelism factor (i.e. the number of sink instances to run)")
        protected String parallelism;
        @Parameter(
                names = "--jar",
                description = "Path to the jar file for the sink",
                listConverter = StringConverter.class)
        protected String jarFile;

        @Parameter(names = "--sinkConfigFile", description = "The path to a YAML config file specifying the "
                + "sink's configuration")
        protected String sinkConfigFile;
        @Parameter(names = "--cpu", description = "The cpu in cores that need to be allocated per function instance(applicable only to docker runtime)")
        protected Double cpu;
        @Parameter(names = "--ram", description = "The ram in bytes that need to be allocated per function instance(applicable only to process/docker runtime)")
        protected Long ram;
        @Parameter(names = "--disk", description = "The disk in bytes that need to be allocated per function instance(applicable only to docker runtime)")
        protected Long disk;
        @Parameter(names = "--sinkConfig", description = "Sink config key/values")
        protected String sinkConfigString;

        protected SinkConfig sinkConfig;

        @Override
        void processArguments() throws Exception {
            super.processArguments();

            if (null != sinkConfigFile) {
                this.sinkConfig = loadSinkConfig(sinkConfigFile);
            } else {
                this.sinkConfig = new SinkConfig();
            }

            if (null != tenant) {
                sinkConfig.setTenant(tenant);
            } else if (sinkConfig.getTenant() == null) {
                sinkConfig.setTenant(PUBLIC_TENANT);
            }
            if (null != namespace) {
                sinkConfig.setNamespace(namespace);
            } else if (sinkConfig.getNamespace() == null) {
                sinkConfig.setNamespace(DEFAULT_NAMESPACE);
            }
            if (null != name) {
                sinkConfig.setName(name);
            }

            if (null != className) {
                sinkConfig.setClassName(className);
            }
            if (null != processingGuarantees) {
                sinkConfig.setProcessingGuarantees(processingGuarantees);
            }
            Map<String, String> topicsToSerDeClassName = new HashMap<>();
            if (null != inputs) {
                List<String> inputTopics = Arrays.asList(inputs.split(","));
                inputTopics.forEach(new Consumer<String>() {
                    @Override
                    public void accept(String s) {
                        CmdSinks.validateTopicName(s);
                        topicsToSerDeClassName.put(s, "");
                    }
                });
            }
            if (null != customSerdeInputString) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> customSerdeInputMap = new Gson().fromJson(customSerdeInputString, type);
                customSerdeInputMap.forEach((topic, serde) -> {
                    CmdSinks.validateTopicName(topic);
                    topicsToSerDeClassName.put(topic, serde);
                });
            }
            sinkConfig.setTopicToSerdeClassName(topicsToSerDeClassName);

            if (parallelism == null) {
                if (sinkConfig.getParallelism() == 0) {
                    sinkConfig.setParallelism(1);
                }
            } else {
                int num = Integer.parseInt(parallelism);
                if (num <= 0) {
                    throw new IllegalArgumentException("The parallelism factor (the number of instances) for the "
                            + "connector must be positive");
                }
                sinkConfig.setParallelism(num);
            }

            if (null == jarFile) {
                throw new IllegalArgumentException("Connector JAR not specfied");
            }

            com.google.common.base.Preconditions.checkArgument(cpu == null || cpu > 0, "The cpu allocation for the sink must be positive");
            com.google.common.base.Preconditions.checkArgument(ram == null || ram > 0, "The ram allocation for the sink must be positive");
            com.google.common.base.Preconditions.checkArgument(disk == null || disk > 0, "The disk allocation for the sink must be positive");
            sinkConfig.setResources(new org.apache.pulsar.functions.utils.Resources(cpu, ram, disk));

            if (null != sinkConfigString) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, Object> sinkConfigMap = new Gson().fromJson(sinkConfigString, type);
                sinkConfig.setConfigs(sinkConfigMap);
            }
        }

        private Class<?> getSinkType(File file) {
            if (!Reflections.classExistsInJar(file, sinkConfig.getClassName())) {
                throw new IllegalArgumentException(String.format("Pulsar sink class %s does not exist in jar %s",
                        sinkConfig.getClassName(), jarFile));
            } else if (!Reflections.classInJarImplementsIface(file, sinkConfig.getClassName(), Sink.class)) {
                throw new IllegalArgumentException(String.format("The Pulsar sink class %s in jar %s implements " + Sink.class.getName(),
                        sinkConfig.getClassName(), jarFile));
            }

            Object userClass = Reflections.createInstance(sinkConfig.getClassName(), file);
            Class<?> typeArg;
            Sink sink = (Sink) userClass;
            if (sink == null) {
                throw new IllegalArgumentException(String.format("The Pulsar sink class %s could not be instantiated from jar %s",
                        sinkConfig.getClassName(), jarFile));
            }
            typeArg = TypeResolver.resolveRawArgument(Sink.class, sink.getClass());

            return typeArg;
        }

        protected org.apache.pulsar.functions.proto.Function.FunctionDetails createSinkConfigProto2(SinkConfig sinkConfig)
                throws IOException {
            org.apache.pulsar.functions.proto.Function.FunctionDetails.Builder functionDetailsBuilder
                    = org.apache.pulsar.functions.proto.Function.FunctionDetails.newBuilder();
            Utils.mergeJson(FunctionsImpl.printJson(createSinkConfig(sinkConfig)), functionDetailsBuilder);
            return functionDetailsBuilder.build();
        }

        protected FunctionDetails createSinkConfig(SinkConfig sinkConfig) {

            File file = new File(jarFile);
            try {
                Reflections.loadJar(file);
            } catch (MalformedURLException e) {
                throw new RuntimeException("Failed to load user jar " + file, e);
            }
            Class<?> typeArg = getSinkType(file);

            FunctionDetails.Builder functionDetailsBuilder = FunctionDetails.newBuilder();
            if (sinkConfig.getTenant() != null) {
                functionDetailsBuilder.setTenant(sinkConfig.getTenant());
            }
            if (sinkConfig.getNamespace() != null) {
                functionDetailsBuilder.setNamespace(sinkConfig.getNamespace());
            }
            if (sinkConfig.getName() != null) {
                functionDetailsBuilder.setName(sinkConfig.getName());
            }
            functionDetailsBuilder.setRuntime(FunctionDetails.Runtime.JAVA);
            functionDetailsBuilder.setParallelism(sinkConfig.getParallelism());
            functionDetailsBuilder.setClassName(IdentityFunction.class.getName());
            if (sinkConfig.getProcessingGuarantees() != null) {
                functionDetailsBuilder.setProcessingGuarantees(
                        convertProcessingGuarantee(sinkConfig.getProcessingGuarantees()));
            }

            // set source spec
            // source spec classname should be empty so that the default pulsar source will be used
            SourceSpec.Builder sourceSpecBuilder = SourceSpec.newBuilder();
            sourceSpecBuilder.setSubscriptionType(Function.SubscriptionType.SHARED);
            sourceSpecBuilder.putAllTopicsToSerDeClassName(sinkConfig.getTopicToSerdeClassName());
            sourceSpecBuilder.setTypeClassName(typeArg.getName());
            functionDetailsBuilder.setAutoAck(true);
            functionDetailsBuilder.setSource(sourceSpecBuilder);

            // set up sink spec
            SinkSpec.Builder sinkSpecBuilder = SinkSpec.newBuilder();
            sinkSpecBuilder.setClassName(sinkConfig.getClassName());
            sinkSpecBuilder.setConfigs(new Gson().toJson(sinkConfig.getConfigs()));
            sinkSpecBuilder.setTypeClassName(typeArg.getName());
            functionDetailsBuilder.setSink(sinkSpecBuilder);

            if (sinkConfig.getResources() != null) {
                Resources.Builder bldr = Resources.newBuilder();
                if (sinkConfig.getResources().getCpu() != null) {
                    bldr.setCpu(sinkConfig.getResources().getCpu());
                }
                if (sinkConfig.getResources().getRam() != null) {
                    bldr.setRam(sinkConfig.getResources().getRam());
                }
                if (sinkConfig.getResources().getDisk() != null) {
                    bldr.setDisk(sinkConfig.getResources().getDisk());
                }
                functionDetailsBuilder.setResources(bldr.build());
            }
            return functionDetailsBuilder.build();
        }
    }

    @Parameters(commandDescription = "Stops a Pulsar sink or source")
    class DeleteSink extends BaseCommand {

        @Parameter(names = "--tenant", description = "The tenant of the sink")
        protected String tenant;

        @Parameter(names = "--namespace", description = "The namespace of the sink")
        protected String namespace;

        @Parameter(names = "--name", description = "The name of the sink")
        protected String name;

        @Override
        void processArguments() throws Exception {
            super.processArguments();
            if (null == tenant || null == namespace || null == name) {
                throw new RuntimeException(
                        "You must specify a tenant, namespace, and name for the sink");
            }
        }

        @Override
        void runCmd() throws Exception {
            admin.functions().deleteFunction(tenant, namespace, name);
            print("Deleted successfully");
        }
    }

    private static SinkConfig loadSinkConfig(String file) throws IOException {
        return (SinkConfig) loadConfig(file, SinkConfig.class);
    }

    private static Object loadConfig(String file, Class<?> clazz) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(file), clazz);
    }

    public static boolean areAllRequiredFieldsPresentForSink(SinkConfig sinkConfig) {
        return sinkConfig.getTenant() != null && !sinkConfig.getTenant().isEmpty()
                && sinkConfig.getNamespace() != null && !sinkConfig.getNamespace().isEmpty()
                && sinkConfig.getName() != null && !sinkConfig.getName().isEmpty()
                && sinkConfig.getClassName() != null && !sinkConfig.getClassName().isEmpty()
                && sinkConfig.getTopicToSerdeClassName() != null && !sinkConfig.getTopicToSerdeClassName().isEmpty()
                && sinkConfig.getParallelism() > 0;
    }

    private static void validateTopicName(String topic) {
        if (!TopicName.isValid(topic)) {
            throw new IllegalArgumentException(String.format("The topic name %s is invalid", topic));
        }
    }

    private static ProcessingGuarantees convertProcessingGuarantee(
            FunctionConfig.ProcessingGuarantees processingGuarantees) {
        for (ProcessingGuarantees type : ProcessingGuarantees.values()) {
            if (type.name().equals(processingGuarantees.name())) {
                return type;
            }
        }
        throw new RuntimeException("Unrecognized processing guarantee: " + processingGuarantees.name());
    }
}
