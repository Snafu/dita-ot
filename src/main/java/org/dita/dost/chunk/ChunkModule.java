/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2021 Jarno Elovirta
 *
 * See the accompanying LICENSE file for applicable license.
 */

package org.dita.dost.chunk;

import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.streams.Step;
import org.dita.dost.chunk.ChunkOperation.ChunkBuilder;
import org.dita.dost.exception.DITAOTException;
import org.dita.dost.module.AbstractPipelineModuleImpl;
import org.dita.dost.module.reader.TempFileNameScheme;
import org.dita.dost.pipeline.AbstractPipelineInput;
import org.dita.dost.pipeline.AbstractPipelineOutput;
import org.dita.dost.util.Job.FileInfo;
import org.dita.dost.util.Job.FileInfo.Builder;
import org.dita.dost.util.URLUtils;
import org.w3c.dom.*;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.sf.saxon.s9api.streams.Steps.attribute;
import static net.sf.saxon.s9api.streams.Steps.descendant;
import static org.dita.dost.chunk.ChunkOperation.Operation.COMBINE;
import static org.dita.dost.module.ChunkModule.ROOT_CHUNK_OVERRIDE;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.FileUtils.getName;
import static org.dita.dost.util.FileUtils.replaceExtension;
import static org.dita.dost.util.URLUtils.*;
import static org.dita.dost.util.XMLUtils.*;

public class ChunkModule extends AbstractPipelineModuleImpl {

    static final String GEN_CHUNK_PREFIX = "Chunk";
    static final String GEN_UNIQUE_PREFIX = "unique_";
    private TempFileNameScheme tempFileNameScheme;
    private String rootChunkOverride;

    @Override
    public AbstractPipelineOutput execute(final AbstractPipelineInput input) throws DITAOTException {
        init(input);
        try {
            // read modifiable map
            final FileInfo in = job.getFileInfo(fi -> fi.isInput).iterator().next();
            final URI mapFile = job.tempDirURI.resolve(in.uri);
            final Document mapDoc = getMap(mapFile);
            List<ChunkOperation> chunks = new ArrayList<>();
            final Map<URI, URI> rewriteMap = new HashMap<>();
            // walk topicref | map
            walk(mapFile, mapDoc.getDocumentElement(), chunks);
            chunks = rewrite(mapFile, rewriteMap, chunks);
            rewriteTopicrefs(mapFile, chunks);
            job.getStore().writeDocument(mapDoc, mapFile);
            // for each chunk
            generateChunks(chunks, rewriteMap);
            removeChunkSources(chunks);
            job.write();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private Document getMap(URI mapFile) throws IOException {
        final Document doc = job.getStore().getDocument(mapFile);
        if (rootChunkOverride != null) {
            logger.debug("Use override root chunk {0}", rootChunkOverride);
            doc.getDocumentElement().setAttribute(ATTRIBUTE_NAME_CHUNK, rootChunkOverride);
        }
        return doc;
    }

    private void init(AbstractPipelineInput input) {
        try {
            tempFileNameScheme = (TempFileNameScheme) Class.forName(job.getProperty("temp-file-name-scheme")).newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        tempFileNameScheme.setBaseDir(job.getInputDir());

        if (input.getAttribute(ROOT_CHUNK_OVERRIDE) != null) {
            rootChunkOverride = input.getAttribute(ROOT_CHUNK_OVERRIDE);
        }
    }

    private void removeChunkSources(List<ChunkOperation> chunks) {
        final Set<URI> sources = new HashSet<>();
        collect(chunks, chunk -> chunk.src, sources);
        final Set<URI> destinations = new HashSet<>();
        collect(chunks, chunk -> chunk.dst, destinations);
        final Set<URI> removed = sources.stream().filter(dst -> !destinations.contains(dst)).collect(Collectors.toSet());
        removed.forEach(tmp -> job.remove(job.getFileInfo(tmp)));
        final Set<URI> added = destinations.stream().filter(dst -> !sources.contains(dst)).collect(Collectors.toSet());
        added.forEach(tmp -> {
            if (job.getFileInfo(tmp) == null) {
                final FileInfo src = chunks.stream()
                        .filter(chunk ->
                                chunk.src != null &&
                                        (chunk.dst != null
                                                ? removeFragment(chunk.dst).equals(tmp)
                                                : false)
                        )
                        .findAny()
                        .flatMap(chunk -> Optional.ofNullable(job.getFileInfo(removeFragment(chunk.src))))
                        .orElse(null);
                final Builder builder = src != null ? FileInfo.builder(src) : FileInfo.builder();
                final URI dstRel = job.tempDirURI.relativize(tmp);
//                final URI result = src != null && src.result != null ? src.result.resolve(tmp) : toDirURI(job.getOutputDir()).resolve(tmp);
                final FileInfo dstFi = builder
                        .uri(dstRel)
                        .format(ATTR_FORMAT_VALUE_DITA)
//                        .result(result)
                        .build();
                job.add(dstFi);
            }
        });
    }

    private void collect(List<ChunkOperation> chunks, final Function<ChunkOperation, URI> pick, Set<URI> res) {
        for (ChunkOperation chunk : chunks) {
            final URI uri = pick.apply(chunk);
            if (uri != null) {
                res.add(removeFragment(uri));
            }
            collect(chunk.children, pick, res);
        }
    }

    private void generateChunks(List<ChunkOperation> chunks, Map<URI, URI> rewriteMap) throws IOException {
        for (ChunkOperation chunk : chunks) {
            //   recursively merge chunk topics
            final Document chunkDoc = merge(chunk);
            rewriteLinks(chunkDoc, chunk, rewriteMap);
            chunkDoc.normalizeDocument();
            final URI dst = removeFragment(chunk.dst);
            job.getStore().writeDocument(chunkDoc, dst);

//            if (job.getFileInfo(dst) == null) {
//                final FileInfo src = chunk.src != null ? job.getFileInfo(removeFragment(chunk.src, null)) : null;
//                final FileInfo.Builder builder = src != null ? FileInfo.builder(src) : FileInfo.builder();
//                final URI dstRel = job.tempDirURI.relativize(dst);
//                final FileInfo dstFi = builder
//                        .uri(dstRel)
//                        // FIXME add result
//                        .build();
//                job.add(dstFi);
//            }
        }
    }

    /**
     * Rewrite topicrefs.
     */
    private void rewriteTopicrefs(final URI mapFile, final List<ChunkOperation> chunks) {
        for (ChunkOperation chunk : chunks) {
            final URI dst = getRelativePath(mapFile.resolve("."), chunk.dst);
            if (!MAP_MAP.matches(chunk.topicref)) {
                chunk.topicref.setAttribute(ATTRIBUTE_NAME_HREF, dst.toString());
            }
            if (MAPGROUP_D_TOPICGROUP.matches(chunk.topicref)) {
                chunk.topicref.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_TOPICREF.toString());
            }
            rewriteTopicrefs(mapFile, chunk.children);
        }
    }

    /**
     * Based on topic moves in chunk, rewrite link in a topic.
     */
    private void rewriteLinks(final Document chunkDoc,
                              final ChunkOperation chunk,
                              final Map<URI, URI> rewriteMap) {
        final List<Element> elements = toList(chunkDoc.getDocumentElement().getElementsByTagName("*"));
        for (Element link : elements) {
            if (TOPIC_LINK.matches(link) || TOPIC_XREF.matches(link)) {
                final URI href = URLUtils.toURI(link.getAttribute(ATTRIBUTE_NAME_HREF));
                final URI abs = chunk.src.resolve(href);
                final URI rewrite = rewriteMap.get(abs);
                if (rewrite != null) {
                    final URI rel = getRelativePath(chunk.src.resolve("."), rewrite);
                    link.setAttribute(ATTRIBUTE_NAME_HREF, rel.toString());
                } else {
                    final URI rel = getRelativePath(chunk.src.resolve("."), abs);
                    link.setAttribute(ATTRIBUTE_NAME_HREF, rel.toString());
                }
            }
        }
    }

    /**
     * Rewrite chunks and collect topic moves.
     */
    private List<ChunkOperation> rewrite(final URI mapFile,
                                         final Map<URI, URI> rewriteMap,
                                         final List<ChunkOperation> chunks) {
        final List<ChunkOperation> list = new ArrayList<>();
        for (ChunkOperation rootChunk : chunks) {
            list.add(rewriteChunk(mapFile, rewriteMap, rootChunk).build());
        }
        return list;
    }

    private ChunkBuilder rewriteChunk(final URI mapFile,
                                      final Map<URI, URI> rewriteMap,
                                      final ChunkOperation rootChunk) {
        String id;
        URI dst;
        if (MAP_MAP.matches(rootChunk.topicref)) {
            id = rootChunk.topicref.getAttribute(ATTRIBUTE_NAME_ID);
            if (id.isEmpty()) {
                id = replaceExtension(getName(mapFile.getPath()), "");
            }
            dst = URI.create(replaceExtension(mapFile.toString(), ".dita"));
            final Collection<URI> values = rewriteMap.values();
//            for (int i = 1; values.contains(dst); i++) {
//                // FIXME
//                id = GEN_CHUNK_PREFIX + i;
//                dst = setFragment(rootChunk.src != null
//                        ? setFragment(rootChunk.src, id)
//                        : mapFile.resolve(id + ".dita"), id);
//            }
        } else {
            if (rootChunk.src != null && rootChunk.src.getFragment() != null) {
                id = rootChunk.src.getFragment();
            } else if (rootChunk.src != null) {
                id = getRootTopicId(rootChunk.src);
            } else {
                id = null;
            }
            dst = rootChunk.src != null ? setFragment(rootChunk.src, id) : mapFile.resolve(GEN_CHUNK_PREFIX + "1.dita");
            final Collection<URI> values = rewriteMap.values();
            for (int i = 1; id == null || values.contains(dst); i++) {
                id = GEN_CHUNK_PREFIX + i;
                dst = setFragment(rootChunk.src != null
                        ? setFragment(rootChunk.src, id)
                        : mapFile.resolve(id + ".dita"), id);
            }
        }
        final ChunkBuilder builder = new ChunkBuilder(rootChunk.operation)
                .topicref(rootChunk.topicref)
                .src(rootChunk.src)
                .dst(dst)
                .id(id);
        rewriteMap.put(rootChunk.src, dst);
        for (ChunkOperation child : rootChunk.children) {
            final ChunkBuilder childBuilder = rewriteChunkChild(
                    rewriteMap,
                    rootChunk.src != null ? rootChunk.src : dst,
                    child);
            builder.addChild(childBuilder);
        }
        return builder;
    }

    private ChunkBuilder rewriteChunkChild(final Map<URI, URI> rewriteMap,
                                           final URI rootChunkSrc,
                                           final ChunkOperation chunk) {
        String id;
        if (chunk.src != null && chunk.src.getFragment() != null) {
            id = chunk.src.getFragment();
        } else if (chunk.src != null) {
            id = getRootTopicId(chunk.src);
        } else {
            id = null;
        }
        URI dst = setFragment(rootChunkSrc, id);
        final Collection<URI> values = rewriteMap.values();
        for (int i = 1; id == null || values.contains(dst); i++) {
            id = GEN_UNIQUE_PREFIX + i;
            dst = setFragment(rootChunkSrc, id);
        }
        final ChunkBuilder builder = new ChunkBuilder(chunk.operation)
                .topicref(chunk.topicref)
                .src(chunk.src)
                .dst(dst)
                .id(id);
        rewriteMap.put(chunk.src, dst);
        for (ChunkOperation child : chunk.children) {
            builder.addChild(rewriteChunkChild(rewriteMap, rootChunkSrc, child));
        }
        return builder;
    }

    /**
     * Get root topic ID.
     */
    private String getRootTopicId(final URI src) {
        try {
            final XdmNode node = job.getStore().getImmutableNode(src);
            final Step<XdmNode> firstTopicId = descendant(TOPIC_TOPIC.matcher()).first()
                    .then(attribute(ATTRIBUTE_NAME_ID));
            return node.select(firstTopicId).asString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Merge chunk tree into a single topic.
     */
    private Document merge(final ChunkOperation rootChunk) throws IOException {
        final Document doc;
        if (rootChunk.src != null) {
            Element dstTopic = getElement(rootChunk.src);
            doc = dstTopic.getOwnerDocument();
            if (dstTopic.getNodeName().equals(ELEMENT_NAME_DITA)) {
                dstTopic = getLastChildTopic(dstTopic);
            } else {
                final Element ditaWrapper = createDita(doc);
                doc.replaceChild(ditaWrapper, doc.getDocumentElement());
                if (dstTopic.getParentNode() != null) {
                    dstTopic = (Element) dstTopic.getParentNode().removeChild(dstTopic);
                }
                ditaWrapper.appendChild(dstTopic);
            }
            mergeTopic(rootChunk, rootChunk, dstTopic);
        } else {
            final Element navtitle = getNavtitle(rootChunk.topicref);
            if (navtitle != null) {
                doc = getDocumentBuilder().newDocument();
                final Element ditaWrapper = createDita(doc);
                doc.appendChild(ditaWrapper);
                final Element topic = createTopic(doc, rootChunk.id);
                topic.appendChild(createTitle(doc, navtitle));
                ditaWrapper.appendChild(topic);
                mergeTopic(rootChunk, rootChunk, topic);
            } else {
                doc = getDocumentBuilder().newDocument();
                final Element ditaWrapper = createDita(doc);
                doc.appendChild(ditaWrapper);
                mergeTopic(rootChunk, rootChunk, ditaWrapper);
            }
        }
        return doc;
    }

    private Element createTitle(final Document doc, final Element src) {
        final Element title = doc.createElement(TOPIC_TITLE.localName);
        title.setAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_TITLE.toString());
        final List<Node> children = toList(src.getChildNodes());
        for (Node child : children) {
            title.appendChild(doc.importNode(child, true));
        }
        return title;
    }

    private Element createDita(final Document doc) {
        final Element ditaWrapper = doc.createElement(ELEMENT_NAME_DITA);
        ditaWrapper.setAttributeNS(DITA_NAMESPACE, ATTRIBUTE_PREFIX_DITAARCHVERSION + ":" + ATTRIBUTE_NAME_DITAARCHVERSION, "2.0");
        return ditaWrapper;
    }

    /**
     * Merge chunk tree fragment into a single topic.
     */
    private void mergeTopic(final ChunkOperation rootChunk,
                            final ChunkOperation chunk,
                            final Element dstTopic) throws IOException {
        for (ChunkOperation child : chunk.children) {
            Element added;
            if (child.src != null) {
                final Element root = getElement(child.src);
                if (root.getNodeName().equals(ELEMENT_NAME_DITA)) {
                    final List<Element> rootTopics = getChildElements(root, TOPIC_TOPIC);
                    int i = 1;
                    for (final Element topic : rootTopics) {
                        final Element imported;
                        imported = (Element) dstTopic.getOwnerDocument().importNode(topic, true);
                        rewriteTopicId(imported, child.id);
                        relativizeLinks(imported, child.src, rootChunk.dst);
                        added = (Element) dstTopic.appendChild(imported);
//                        if (i++ == rootTopics.size()) {
//                        }
                    }
                    mergeTopic(rootChunk, child, dstTopic);
                } else {
                    final Element imported = (Element) dstTopic.getOwnerDocument().importNode(root, true);
                    rewriteTopicId(imported, child.id);
                    relativizeLinks(imported, child.src, rootChunk.dst);
                    added = (Element) dstTopic.appendChild(imported);
                    mergeTopic(rootChunk, child, added);
                }
            } else {
                final Element imported = createTopic(dstTopic.getOwnerDocument(), child.id);
                final Element navtitle = getNavtitle(child.topicref);
                if (navtitle != null) {
                    imported.appendChild(createTitle(dstTopic.getOwnerDocument(), navtitle));
                }
                added = (Element) dstTopic.appendChild(imported);
                mergeTopic(rootChunk, child, added);
            }
        }
    }

    private Element getElement(URI src) throws IOException {
        final Document chunkDoc = job.getStore().getDocument(src);
        if (src.getFragment() != null) {
            final NodeList children = chunkDoc.getElementsByTagName("*");
            for (int i = 0; i < children.getLength(); i++) {
                final Node child = children.item(i);
                if (TOPIC_TOPIC.matches(child)
                        && ((Element) child).getAttribute(ATTRIBUTE_NAME_ID).equals(src.getFragment())) {
                    return (Element) child;
                }
            }
            return null;
        } else {
            return chunkDoc.getDocumentElement();
        }
    }

    private List<Element> getRootTopics(final Document chunkDoc) {
        final Element root = chunkDoc.getDocumentElement();
        if (root.getNodeName().equals(ELEMENT_NAME_DITA)) {
            return getChildElements(root, TOPIC_TOPIC);
        } else {
            return Collections.singletonList(root);
        }
    }

    private Element getLastChildTopic(final Element dita) {
        final List<Element> childElements = getChildElements(dita, TOPIC_TOPIC);
        if (childElements.isEmpty()) {
            return null;
        }
        return childElements.get(childElements.size() - 1);
    }

    private Element createTopic(final Document doc, final String id) {
        final Element imported = doc.createElement(TOPIC_TOPIC.localName);
        imported.setAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_TOPIC.toString());
        imported.setAttribute(ATTRIBUTE_NAME_ID, id);
        return imported;
    }

    private void rewriteTopicId(final Element topic, final String id) {
        topic.setAttribute(ATTRIBUTE_NAME_ID, id);
    }

    private void relativizeLinks(final Element topic, final URI src, final URI dst) {
        for (Element link : getChildElements(topic, TOPIC_LINK, true)) {
            final URI href = URLUtils.toURI(link.getAttribute(ATTRIBUTE_NAME_HREF));
            final URI abs = src.resolve(href);
            final URI rel = getRelativePath(dst.resolve("."), abs);
            link.setAttribute(ATTRIBUTE_NAME_HREF, rel.toString());
        }
    }

    /**
     * Walk map and collect chunks.
     */
    private void walk(final URI mapFile, final Element elem, final List<ChunkOperation> chunks) {
        final String chunk = elem.getAttribute(ATTRIBUTE_NAME_CHUNK);
        //   if @chunk = COMBINE
        if (chunk.equals(COMBINE.name)) {
            if (MAP_MAP.matches(elem)) {
                //     create chunk
                final URI href = URI.create(replaceExtension(mapFile.toString(), ".dita"));
                final ChunkBuilder builder = new ChunkBuilder(COMBINE)
                        //.src(href)
                        .dst(href)
                        .topicref(elem);
                //     remove contents
                //                elem.removeChild(child);
                getChildElements(elem, MAP_TOPICREF).stream()
                        .flatMap(child -> collect(mapFile, child).stream())
                        .forEachOrdered(builder::addChild);
                // remove @chunk
                elem.removeAttribute(ATTRIBUTE_NAME_CHUNK);
                chunks.add(builder.build());
                return;
            } else {
                //     create chunk
                final Attr hrefNode = elem.getAttributeNode(ATTRIBUTE_NAME_HREF);
                final URI href = hrefNode != null ? mapFile.resolve(hrefNode.getValue()) : null;
                final ChunkBuilder builder = new ChunkBuilder(COMBINE)
                        .src(href)
//                    .dst(href)
                        .topicref(elem);
                //     remove contents
                //                elem.removeChild(child);
                getChildElements(elem, MAP_TOPICREF).stream()
                        .flatMap(child -> collect(mapFile, child).stream())
                        .forEachOrdered(builder::addChild);
                // remove @chunk
                elem.removeAttribute(ATTRIBUTE_NAME_CHUNK);
                chunks.add(builder.build());
                return;
            }
        } else {
            for (Element child : getChildElements(elem, MAP_TOPICREF)) {
                walk(mapFile, child, chunks);
            }
        }
    }

    private List<ChunkBuilder> collect(final URI mapFile, final Element elem) {
        final Attr hrefNode = elem.getAttributeNode(ATTRIBUTE_NAME_HREF);
        final Element navtitle = getNavtitle(elem);
        if (hrefNode != null && isDitaFormat(elem) && isLocalScope(elem)) {
            final URI href = mapFile.resolve(hrefNode.getValue());
            final ChunkBuilder builder = new ChunkBuilder(COMBINE)
                    .src(href)
                    .topicref(elem);
            for (Element child : getChildElements(elem, MAP_TOPICREF)) {
                for (ChunkBuilder chunkBuilder : collect(mapFile, child)) {
                    builder.addChild(chunkBuilder);
                }
            }
            return Collections.singletonList(builder);
        } else if (navtitle != null) {
            final ChunkBuilder builder = new ChunkBuilder(COMBINE)
                    .topicref(elem);
            for (Element child : getChildElements(elem, MAP_TOPICREF)) {
                for (ChunkBuilder chunkBuilder : collect(mapFile, child)) {
                    builder.addChild(chunkBuilder);
                }
            }
            return Collections.singletonList(builder);
        } else {
            return getChildElements(elem, MAP_TOPICREF).stream()
                    .flatMap(child -> collect(mapFile, child).stream())
                    .collect(Collectors.toList());
        }
    }

    private Element getNavtitle(final Element topicref) {
        if (topicref != null) {
            return getChildElement(topicref, MAP_TOPICMETA)
                    .flatMap(topicmeta -> getChildElement(topicmeta, TOPIC_NAVTITLE))
                    .orElse(null);
        }
        return null;
    }

    public boolean isDitaFormat(final Element elem) {
        final String format = elem.getAttribute(ATTRIBUTE_NAME_FORMAT);
        return format.isEmpty() || format.equals(ATTR_FORMAT_VALUE_DITA);
    }

    public static boolean isLocalScope(final Element elem) {
        final String scope = elem.getAttribute(ATTRIBUTE_NAME_SCOPE);
        return scope.isEmpty() || scope.equals(ATTR_SCOPE_VALUE_LOCAL);
    }
}
