/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2018 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.transform.client.model.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.alfresco.repo.rendition2.RenditionDefinition2.TIMEOUT;

/**
 * Used by clients to work out if a transformation is supported by the Transform Service.
 */
public class TransformServiceRegistryImpl implements TransformServiceRegistry, InitializingBean
{
    class SupportedTransform
    {
        TransformOptionGroup transformOptions;
        long maxSourceSizeBytes;
        private String name;
        private int priority;

        public SupportedTransform(String name, List<TransformOption> transformOptions, long maxSourceSizeBytes, int priority)
        {
            // Logically the top level TransformOptionGroup is required, so that child options are optional or required
            // based on their own setting.
            this.transformOptions = new TransformOptionGroup(true, transformOptions);
            this.maxSourceSizeBytes = maxSourceSizeBytes;
            this.name = name;
            this.priority = priority;
        }
    }

    private ObjectMapper jsonObjectMapper;
    private ExtensionMap extensionMap;

    ConcurrentMap<String, ConcurrentMap<String, List<SupportedTransform>>> transformers = new ConcurrentHashMap<>();
    ConcurrentMap<String, ConcurrentMap<String, List<SupportedTransform>>> cachedSupportedTransformList = new ConcurrentHashMap<>();

    public void setJsonObjectMapper(ObjectMapper jsonObjectMapper)
    {
        this.jsonObjectMapper = jsonObjectMapper;
    }

    public ExtensionMap getExtensionMap()
    {
        return extensionMap;
    }

    public void setExtensionMap(ExtensionMap extensionMap)
    {
        this.extensionMap = extensionMap;
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        if (jsonObjectMapper == null)
        {
            throw new IllegalStateException("jsonObjectMapper has not been set");
        }
        if (extensionMap == null)
        {
            throw new IllegalStateException("extensionMap has not been set");
        }
    }

    private String toMimetype(String ext)
    {
        String mimetype = extensionMap.toMimetype(ext);
        if (mimetype == null)
        {
            throw new IllegalArgumentException("The mimetype for the file extension "+ext+" cannot be looked up by: "+
                    extensionMap.getClass().getName());
        }
        return mimetype;
    }

    public void register(Transformer transformer)
    {
        transformer.getSupportedSourceAndTargetList().forEach(
            e -> transformers.computeIfAbsent(toMimetype(e.getSourceExt()),
                k -> new ConcurrentHashMap<>()).computeIfAbsent(toMimetype(e.getTargetExt()),
                k -> new ArrayList<>()).add(
                    new SupportedTransform(transformer.getName(),
                            transformer.getTransformOptions(), e.getMaxSourceSizeBytes(), e.getPriority())));
    }

    public void register(Reader reader) throws IOException
    {
        List<Transformer> transformers = jsonObjectMapper.readValue(reader, new TypeReference<List<Transformer>>(){});
        transformers.forEach(t -> register(t));
    }

    @Override
    public boolean isSupported(String sourceMimetype, long sourceSizeInBytes, String targetMimetype,
                               Map<String, String> actualOptions, String transformName)
    {
        long maxSize = getMaxSize(sourceMimetype, targetMimetype, actualOptions, transformName);
        return maxSize != 0 && (maxSize == -1L || maxSize >= sourceSizeInBytes);
    }

    /**
     * Works out the name of the transformer (might not map to an actual transformer) that will be used to transform
     * content of a given source mimetype and size into a target mimetype given a list of actual transform option names
     * and values (Strings) plus the data contained in the {@Transform} objects registered with this class.
     * @param sourceMimetype the mimetype of the source content
     * @param sourceSizeInBytes the size in bytes of the source content. Ignored if negative.
     * @param targetMimetype the mimetype of the target
     * @param actualOptions the actual name value pairs available that could be passed to the Transform Service.
     * @param transformName (optional) name for the set of options and target mimetype. If supplied is used to cache
     *                      results to avoid having to work out if a given transformation is supported a second time.
     *                      The sourceMimetype and sourceSizeInBytes may still change. In the case of ACS this is the
     *                      rendition name.
     */
    protected String getTransformerName(String sourceMimetype, long sourceSizeInBytes, String targetMimetype, Map<String, String> actualOptions, String transformName)
    {
        List<SupportedTransform> supportedTransforms = getTransformListBySize(sourceMimetype, targetMimetype, actualOptions, transformName);
        for (SupportedTransform supportedTransform : supportedTransforms)
        {
            if (supportedTransform.maxSourceSizeBytes == -1 || supportedTransform.maxSourceSizeBytes >= sourceSizeInBytes)
            {
                return supportedTransform.name;
            }
        }

        return null;
    }

    @Override
    public long getMaxSize(String sourceMimetype, String targetMimetype,
                           Map<String, String> actualOptions, String transformName)
    {
        List<SupportedTransform> supportedTransforms = getTransformListBySize(sourceMimetype, targetMimetype, actualOptions, transformName);
        return supportedTransforms.isEmpty() ? 0 : supportedTransforms.get(supportedTransforms.size()-1).maxSourceSizeBytes;
    }

    // Returns transformers in increasing supported size order, where lower priority transformers for the same size have
    // been discarded.
    private List<SupportedTransform> getTransformListBySize(String sourceMimetype, String targetMimetype,
                                                            Map<String, String> actualOptions, String transformName)
    {
        if (actualOptions == null)
        {
            actualOptions = Collections.EMPTY_MAP;
        }
        if (transformName != null && transformName.trim().isEmpty())
        {
            transformName = null;
        }

        List<SupportedTransform> transformListBySize = transformName == null ? null
                : cachedSupportedTransformList.computeIfAbsent(transformName, k -> new ConcurrentHashMap<>()).get(sourceMimetype);
        if (transformListBySize != null)
        {
            return transformListBySize;
        }

        // Remove the "timeout" property from the actualOptions as it is not used to select a transformer.
        if (actualOptions.containsKey(TIMEOUT))
        {
            actualOptions = new HashMap(actualOptions);
            actualOptions.remove(TIMEOUT);
        }

        transformListBySize = new ArrayList<>();
        ConcurrentMap<String, List<SupportedTransform>> targetMap = transformers.get(sourceMimetype);
        if (targetMap !=  null)
        {
            List<SupportedTransform> supportedTransformList = targetMap.get(targetMimetype);
            if (supportedTransformList != null)
            {
                for (SupportedTransform supportedTransform : supportedTransformList)
                {
                    TransformOptionGroup transformOptions = supportedTransform.transformOptions;
                    Map<String, Boolean> possibleTransformOptions = new HashMap<>();
                    addToPossibleTransformOptions(possibleTransformOptions, transformOptions, true, actualOptions);
                    if (isSupported(possibleTransformOptions, actualOptions))
                    {
                        addToSupportedTransformList(transformListBySize, supportedTransform);
                    }
                }
            }
        }

        if (transformName != null)
        {
            cachedSupportedTransformList.get(transformName).put(sourceMimetype, transformListBySize);
        }

        return transformListBySize;
    }

    // Add newTransform to the transformListBySize in increasing size order and discards lower priority (numerically
    // higher) transforms with a smaller or equal size.
    private void addToSupportedTransformList(List<SupportedTransform> transformListBySize, SupportedTransform newTransform)
    {
        for (int i=0; i < transformListBySize.size(); i++)
        {
            SupportedTransform existingTransform = transformListBySize.get(i);
            int added = -1;
            int compare = compare(newTransform.maxSourceSizeBytes, existingTransform.maxSourceSizeBytes);
            if (compare < 0)
            {
                transformListBySize.add(i, newTransform);
                added = i;
            }
            else if (compare == 0)
            {
                if (newTransform.priority < existingTransform.priority)
                {
                    transformListBySize.set(i, newTransform);
                    added = i;
                }
            }
            if (added == i)
            {
                for (i--; i >= 0; i--)
                {
                    existingTransform = transformListBySize.get(i);
                    if (newTransform.priority <= existingTransform.priority)
                    {
                        transformListBySize.remove(i);
                    }
                }
                return;
            }
        }
        transformListBySize.add(newTransform);
    }

    // compare where -1 is unlimited.
    private int compare(long a, long b)
    {
        return a == -1
                ? b == -1 ? 0 : 1
                : b == -1 ? -1
                : a == b ? 0
                : a > b ? 1 : -1;
    }

    /**
     * Flatten out the transform options by adding them to the supplied possibleTransformOptions.</p>
     *
     * If possible discards options in the supplied transformOptionGroup if the group is optional and the actualOptions
     * don't provide any of the options in the group. Or to put it another way:<p/>
     *
     * It adds individual transform options from the transformOptionGroup to possibleTransformOptions if the group is
     * required or if the actualOptions include individual options from the group. As a result it is possible that none
     * of the group are added if it is optional. It is also possible to add individual transform options that are
     * themselves required but not in the actualOptions. In this the isSupported method will return false.
     * @return true if any options were added. Used by nested call parents to determine if an option was added from a
     * nested sub group.
     */
    boolean addToPossibleTransformOptions(Map<String, Boolean> possibleTransformOptions,
                                          TransformOptionGroup transformOptionGroup,
                                          Boolean parentGroupRequired, Map<String, String> actualOptions)
    {
        boolean added = false;
        boolean required = false;

        List<TransformOption> optionList = transformOptionGroup.getTransformOptions();
        if (optionList != null && !optionList.isEmpty())
        {
            // We need to avoid adding options from a group that is required but its parents are not.
            boolean transformOptionGroupRequired = transformOptionGroup.isRequired() && parentGroupRequired;

            // Check if the group contains options in actualOptions. This will add any options from sub groups.
            for (TransformOption transformOption : optionList)
            {
                if (transformOption instanceof TransformOptionGroup)
                {
                    added = addToPossibleTransformOptions(possibleTransformOptions, (TransformOptionGroup) transformOption,
                            transformOptionGroupRequired, actualOptions);
                    required |= added;
                }
                else
                {
                    String name = ((TransformOptionValue) transformOption).getName();
                    if (actualOptions.containsKey(name))
                    {
                        required = true;
                    }
                }
            }

            if (required || transformOptionGroupRequired)
            {
                for (TransformOption transformOption : optionList)
                {
                    if (transformOption instanceof TransformOptionValue)
                    {
                        added = true;
                        TransformOptionValue transformOptionValue = (TransformOptionValue) transformOption;
                        String name = transformOptionValue.getName();
                        boolean optionValueRequired = transformOptionValue.isRequired();
                        possibleTransformOptions.put(name, optionValueRequired);
                    }
                }
            }
        }

        return added;
    }

    boolean isSupported(Map<String, Boolean> transformOptions, Map<String, String> actualOptions)
    {
        boolean supported = true;

        // Check all required transformOptions are supplied
        for (Map.Entry<String, Boolean> transformOption : transformOptions.entrySet())
        {
            Boolean required = transformOption.getValue();
            if (required)
            {
                String name = transformOption.getKey();
                if (!actualOptions.containsKey(name))
                {
                    supported = false;
                    break;
                }
            }
        }

        if (supported)
        {
            // Check there are no extra unused actualOptions
            for (String actualOption : actualOptions.keySet())
            {
                if (!transformOptions.containsKey(actualOption))
                {
                    supported = false;
                    break;
                }
            }
        }
        return supported;
    }
}