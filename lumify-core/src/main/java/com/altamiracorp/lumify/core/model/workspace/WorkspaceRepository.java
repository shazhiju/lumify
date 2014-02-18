package com.altamiracorp.lumify.core.model.workspace;

import com.altamiracorp.lumify.core.model.ontology.Concept;
import com.altamiracorp.lumify.core.model.ontology.OntologyLumifyProperties;
import com.altamiracorp.lumify.core.model.ontology.OntologyRepository;
import com.altamiracorp.lumify.core.model.ontology.Relationship;
import com.altamiracorp.lumify.core.model.user.UserRepository;
import com.altamiracorp.lumify.core.user.User;
import com.altamiracorp.lumify.core.user.UserProvider;
import com.altamiracorp.securegraph.*;
import com.altamiracorp.securegraph.util.ConvertingIterable;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.altamiracorp.securegraph.util.IterableUtils.toList;
import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class WorkspaceRepository {
    public static final String VISIBILITY_STRING = "workspace";
    public static final String WORKSPACE_CONCEPT_NAME = "http://lumify.io/workspace";
    public static final String WORKSPACE_TO_ENTITY_RELATIONSHIP_NAME = "http://lumify.io/workspace/toEntity";
    public static final String WORKSPACE_TO_USER_RELATIONSHIP_NAME = "http://lumify.io/workspace/toUser";
    private final Graph graph;
    private final String workspaceConceptId;
    private final String workspaceToEntityRelationshipId;
    private final String workspaceToUserRelationshipId;
    private final UserRepository userRepository;

    @Inject
    public WorkspaceRepository(
            final Graph graph,
            final UserProvider userProvider,
            final OntologyRepository ontologyRepository,
            final UserRepository userRepository) {
        this.graph = graph;
        this.userRepository = userRepository;

        Concept entityConcept = ontologyRepository.getConceptByName(OntologyRepository.TYPE_ENTITY);

        Concept workspaceConcept = ontologyRepository.getOrCreateConcept(null, WORKSPACE_CONCEPT_NAME, "workspace");
        workspaceConceptId = workspaceConcept.getId();

        Relationship workspaceToEntityRelationship = ontologyRepository.getOrCreateRelationshipType(workspaceConcept, entityConcept, WORKSPACE_TO_ENTITY_RELATIONSHIP_NAME, "workspace to entity");
        workspaceToEntityRelationshipId = workspaceToEntityRelationship.getId();

        Relationship workspaceToUserRelationship = ontologyRepository.getOrCreateRelationshipType(workspaceConcept, entityConcept, WORKSPACE_TO_USER_RELATIONSHIP_NAME, "workspace to user");
        workspaceToUserRelationshipId = workspaceToUserRelationship.getId();
    }

    public void delete(Workspace workspace, User user) {
        Authorizations authorizations = user.getAuthorizations(VISIBILITY_STRING, workspace.getId());
        graph.removeVertex(workspace.getVertex(), authorizations);
        graph.flush();
    }

    public Workspace findById(String workspaceId, User user) {
        Authorizations authorizations = user.getAuthorizations(VISIBILITY_STRING, workspaceId);
        Vertex workspaceVertex = graph.getVertex(workspaceId, authorizations);
        return new Workspace(workspaceVertex, this, user);
    }

    public Workspace add(String title, User user) {
        Vertex userVertex = this.userRepository.findById(user.getUserId());
        checkNotNull(userVertex, "Could not find user: " + user.getUserId());

        String workspaceId = "WORKSPACE_" + graph.getIdGenerator().nextId();
        Visibility visibility = getVisibility(workspaceId);
        Authorizations authorizations = user.getAuthorizations(VISIBILITY_STRING, workspaceId);
        VertexBuilder workspaceVertexBuilder = graph.prepareVertex(workspaceId, visibility, authorizations);
        OntologyLumifyProperties.CONCEPT_TYPE.setProperty(workspaceVertexBuilder, workspaceConceptId, visibility);
        WorkspaceLumifyProperties.TITLE.setProperty(workspaceVertexBuilder, title, visibility);
        Vertex workspaceVertex = workspaceVertexBuilder.save();

        EdgeBuilder edgeBuilder = graph.prepareEdge(workspaceVertex, userVertex, workspaceToUserRelationshipId, visibility, authorizations);
        WorkspaceLumifyProperties.WORKSPACE_TO_USER.setProperty(edgeBuilder, true, visibility);
        edgeBuilder.save();

        userRepository.addAuthorization(user, userVertex, workspaceId);

        graph.flush();
        return new Workspace(workspaceVertex, this, user);
    }

    private Visibility getVisibility(String workspaceId) {
        return new Visibility(VISIBILITY_STRING + "&" + workspaceId);
    }

    public Iterable<Workspace> findAll(User user) {
        Authorizations authorizations = user.getAuthorizations(VISIBILITY_STRING);
        Iterable<Vertex> vertices = graph.query(authorizations)
                .has(OntologyLumifyProperties.CONCEPT_TYPE.getKey(), workspaceConceptId)
                .vertices();
        return Workspace.toWorkspaceIterable(vertices, this, user);
    }

    public void setTitle(Workspace workspace, String title) {
        Visibility visibility = getVisibility(workspace.getId());
        WorkspaceLumifyProperties.TITLE.setProperty(workspace.getVertex(), title, visibility);
        graph.flush();
    }

    /**
     * The first user will be the creator.
     */
    public List<Vertex> findUsersWithAccess(final Workspace workspace, final User user) {
        Authorizations authorizations = user.getAuthorizations(VISIBILITY_STRING, workspace.getId());
        List<Edge> userEdges = toList(workspace.getVertex().query(authorizations).edges(workspaceToUserRelationshipId));
        Collections.sort(userEdges, new Comparator<Edge>() {
            @Override
            public int compare(Edge o1, Edge o2) {
                Boolean isCreator1 = WorkspaceLumifyProperties.WORKSPACE_TO_USER.getPropertyValue(o1);
                if (isCreator1 != null && isCreator1) {
                    return -1;
                }
                Boolean isCreator2 = WorkspaceLumifyProperties.WORKSPACE_TO_USER.getPropertyValue(o2);
                if (isCreator2 != null && isCreator2) {
                    return 1;
                }
                return 0;
            }
        });
        return toList(new ConvertingIterable<Edge, Vertex>(userEdges) {
            @Override
            protected Vertex convert(Edge edge) {
                String userId = edge.getOtherVertexId(workspace.getVertex().getId()).toString();
                return userRepository.findById(userId);
            }
        });
    }

    public Workspace copy(Workspace workspace, User authUser) {
        throw new RuntimeException("TODO workspace");
//        Workspace originalWorkspace = workspaceRepository.findByRowKey(originalRowKey, authUser.getModelUserContext());
//        Workspace workspace = createNewWorkspace(originalWorkspace.getMetadata().getTitle(), user);
//
//        if (originalWorkspace.getContent().getData() != null) {
//            workspace.getContent().setData(originalWorkspace.getContent().getData());
//        }
//
//        workspaceRepository.save(workspace, authUser.getModelUserContext());

//        public Workspace createNewWorkspace(String title, Vertex user) {
//            WorkspaceRowKey workspaceRowKey = new WorkspaceRowKey(
//                    user.getId().toString(), String.valueOf(System.currentTimeMillis()));
//            Workspace workspace = new Workspace(workspaceRowKey);
//
//            workspace.getMetadata().setTitle("Copy of " + title);
//            workspace.getMetadata().setCreator(user.getId().toString());
//
//            return workspace;
//        }

    }
}
