/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.EventStreamInfo;
import software.amazon.smithy.model.knowledge.KnowledgeIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ToShapeId;

/**
 * Provides a knowledge index about event streams and their corresponding usage in operations.
 */
public class GoEventStreamIndex implements KnowledgeIndex {
    final Map<ShapeId, Map<ShapeId, Set<EventStreamInfo>>> inputEventStreams = new HashMap<>();
    final Map<ShapeId, Map<ShapeId, Set<EventStreamInfo>>> outputEventStreams = new HashMap<>();

    public GoEventStreamIndex(Model model) {
        EventStreamIndex eventStreamIndex = EventStreamIndex.of(model);

        for (ServiceShape serviceShape : model.getServiceShapes()) {
            final Map<ShapeId, Set<EventStreamInfo>> serviceInputStreams = new HashMap<>();
            final Map<ShapeId, Set<EventStreamInfo>> serviceOutputStreams = new HashMap<>();
            TopDownIndex.of(model).getContainedOperations(serviceShape).forEach(operationShape -> {
                eventStreamIndex.getInputInfo(operationShape).ifPresent(eventStreamInfo -> {
                    ShapeId eventStreamTargetId = eventStreamInfo.getEventStreamTarget().getId();
                    if (serviceInputStreams.containsKey(eventStreamTargetId)) {
                        serviceInputStreams.get(eventStreamTargetId).add(eventStreamInfo);
                    } else {
                        TreeSet<EventStreamInfo> infos = new TreeSet<>(
                                Comparator.comparing(EventStreamInfo::getOperation));
                        infos.add(eventStreamInfo);
                        serviceInputStreams.put(eventStreamTargetId, infos);
                    }
                });
                eventStreamIndex.getOutputInfo(operationShape).ifPresent(eventStreamInfo -> {
                    ShapeId eventStreamTargetId = eventStreamInfo.getEventStreamTarget().getId();
                    if (serviceOutputStreams.containsKey(eventStreamTargetId)) {
                        serviceOutputStreams.get(eventStreamTargetId).add(eventStreamInfo);
                    } else {
                        TreeSet<EventStreamInfo> infos = new TreeSet<>(
                                Comparator.comparing(EventStreamInfo::getOperation));
                        infos.add(eventStreamInfo);
                        serviceOutputStreams.put(eventStreamTargetId, infos);
                    }
                });
            });
            if (!serviceInputStreams.isEmpty()) {
                inputEventStreams.put(serviceShape.getId(), serviceInputStreams);
            }
            if (!serviceOutputStreams.isEmpty()) {
                outputEventStreams.put(serviceShape.getId(), serviceOutputStreams);
            }
        }
    }

    /**
     * Get the input event streams and their usages in operations for the provided service.
     *
     * @param service the service shape
     * @return the map of event stream unions to their corresponding event infos
     */
    public Optional<Map<ShapeId, Set<EventStreamInfo>>> getInputEventStreams(ToShapeId service) {
        return Optional.ofNullable(inputEventStreams.get(service.toShapeId()));
    }

    /**
     * Get the output event streams and their usages in operations for the provided service.
     *
     * @param service the service shape
     * @return the map of event stream unions to their corresponding event infos
     */
    public Optional<Map<ShapeId, Set<EventStreamInfo>>> getOutputEventStreams(ToShapeId service) {
        return Optional.ofNullable(outputEventStreams.get(service.toShapeId()));
    }

    /**
     * Returns a {@link GoEventStreamIndex} for the given model.
     *
     * @param model the model
     * @return the knowledge index
     */
    public static GoEventStreamIndex of(Model model) {
        return model.getKnowledge(GoEventStreamIndex.class, GoEventStreamIndex::new);
    }
}
