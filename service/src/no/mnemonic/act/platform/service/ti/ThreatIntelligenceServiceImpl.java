package no.mnemonic.act.platform.service.ti;

import no.mnemonic.act.platform.api.exceptions.*;
import no.mnemonic.act.platform.api.model.v1.*;
import no.mnemonic.act.platform.api.model.v1.Object;
import no.mnemonic.act.platform.api.request.v1.*;
import no.mnemonic.act.platform.api.service.v1.RequestHeader;
import no.mnemonic.act.platform.api.service.v1.ResultSet;
import no.mnemonic.act.platform.api.service.v1.ThreatIntelligenceService;
import no.mnemonic.act.platform.auth.IdentityResolver;
import no.mnemonic.act.platform.auth.OrganizationResolver;
import no.mnemonic.act.platform.auth.SubjectResolver;
import no.mnemonic.act.platform.dao.cassandra.FactManager;
import no.mnemonic.act.platform.dao.cassandra.ObjectManager;
import no.mnemonic.act.platform.dao.cassandra.entity.FactEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectFactBindingEntity;
import no.mnemonic.act.platform.dao.elastic.FactSearchManager;
import no.mnemonic.act.platform.service.Service;
import no.mnemonic.act.platform.service.contexts.RequestContext;
import no.mnemonic.act.platform.service.contexts.SecurityContext;
import no.mnemonic.act.platform.service.ti.converters.*;
import no.mnemonic.act.platform.service.ti.delegates.*;
import no.mnemonic.act.platform.service.ti.helpers.FactStorageHelper;
import no.mnemonic.act.platform.service.ti.helpers.FactTypeHelper;
import no.mnemonic.act.platform.service.ti.helpers.FactTypeResolver;
import no.mnemonic.act.platform.service.ti.helpers.ObjectResolver;
import no.mnemonic.act.platform.service.validators.ValidatorFactory;
import no.mnemonic.commons.utilities.ObjectUtils;
import no.mnemonic.services.common.auth.AccessController;
import no.mnemonic.services.common.auth.model.Credentials;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ThreatIntelligenceServiceImpl implements Service, ThreatIntelligenceService {

  public static final UUID GLOBAL_NAMESPACE = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private final AccessController accessController;
  private final IdentityResolver identityResolver;
  private final OrganizationResolver organizationResolver;
  private final SubjectResolver subjectResolver;
  private final FactManager factManager;
  private final ObjectManager objectManager;
  private final FactSearchManager factSearchManager;
  private final ValidatorFactory validatorFactory;
  private final ObjectTypeConverter objectTypeConverter;
  private final FactTypeConverter factTypeConverter;
  private final ObjectConverter objectConverter;
  private final FactConverter factConverter;
  private final AclEntryConverter aclEntryConverter;
  private final FactCommentConverter factCommentConverter;

  @Inject
  public ThreatIntelligenceServiceImpl(AccessController accessController, IdentityResolver identityResolver,
                                       OrganizationResolver organizationResolver, SubjectResolver subjectResolver,
                                       FactManager factManager, ObjectManager objectManager, FactSearchManager factSearchManager,
                                       ValidatorFactory validatorFactory) {
    this.accessController = accessController;
    this.identityResolver = identityResolver;
    this.organizationResolver = organizationResolver;
    this.subjectResolver = subjectResolver;
    this.factManager = factManager;
    this.objectManager = objectManager;
    this.factSearchManager = factSearchManager;
    this.validatorFactory = validatorFactory;
    this.objectTypeConverter = ObjectTypeConverter.builder()
            .setNamespaceConverter(createNamespaceConverter())
            .build();
    this.factTypeConverter = FactTypeConverter.builder()
            .setNamespaceConverter(createNamespaceConverter())
            .setObjectTypeConverter(createObjectTypeByIdConverter())
            .setFactTypeEntityResolver(factManager::getFactType)
            .build();
    this.objectConverter = ObjectConverter.builder()
            .setObjectTypeConverter(createObjectTypeByIdConverter())
            .setFactTypeConverter(createFactTypeByIdConverter())
            // Don't include statistics by default, e.g. when including Objects as part of Facts.
            .setFactStatisticsResolver(objectID -> Collections.emptyList())
            .build();
    this.factConverter = FactConverter.builder()
            .setFactTypeConverter(createFactTypeByIdConverter())
            .setOrganizationConverter(organizationResolver::resolveOrganization)
            .setSourceConverter(createSourceConverter())
            .setObjectConverter(createObjectByIdConverter())
            .setFactEntityResolver(factManager::getFact)
            .setAccessChecker(fact -> TiSecurityContext.get().hasReadPermission(fact))
            .build();
    this.aclEntryConverter = AclEntryConverter.builder()
            .setSourceConverter(createSourceConverter())
            .setSubjectConverter(subjectResolver::resolveSubject)
            .build();
    this.factCommentConverter = FactCommentConverter.builder()
            .setSourceConverter(createSourceConverter())
            .build();
  }

  @Override
  public SecurityContext createSecurityContext(Credentials credentials) {
    return TiSecurityContext.builder()
            .setAccessController(accessController)
            .setIdentityResolver(identityResolver)
            .setOrganizationResolver(organizationResolver)
            .setSubjectResolver(subjectResolver)
            .setCredentials(credentials)
            .setAclResolver(factManager::fetchFactAcl)
            .setFactsBoundToObjectResolver(createFactsBoundToObjectResolver())
            .build();
  }

  @Override
  public RequestContext createRequestContext() {
    return TiRequestContext.builder()
            .setFactManager(factManager)
            .setObjectManager(objectManager)
            .setFactSearchManager(factSearchManager)
            .setValidatorFactory(validatorFactory)
            .setObjectTypeConverter(objectTypeConverter)
            .setFactTypeConverter(factTypeConverter)
            .setObjectConverter(objectConverter)
            .setFactConverter(factConverter)
            .setAclEntryConverter(aclEntryConverter)
            .setFactCommentConverter(factCommentConverter)
            .build();
  }

  @Override
  public ObjectType getObjectType(RequestHeader rh, GetObjectTypeByIdRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return ObjectTypeGetByIdDelegate.create().handle(request);
  }

  @Override
  public ResultSet<ObjectType> searchObjectTypes(RequestHeader rh, SearchObjectTypeRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return ObjectTypeSearchDelegate.create().handle(request);
  }

  @Override
  public ObjectType createObjectType(RequestHeader rh, CreateObjectTypeRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return ObjectTypeCreateDelegate.create().handle(request);
  }

  @Override
  public ObjectType updateObjectType(RequestHeader rh, UpdateObjectTypeRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return ObjectTypeUpdateDelegate.create().handle(request);
  }

  @Override
  public FactType getFactType(RequestHeader rh, GetFactTypeByIdRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactTypeGetByIdDelegate.create().handle(request);
  }

  @Override
  public ResultSet<FactType> searchFactTypes(RequestHeader rh, SearchFactTypeRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return FactTypeSearchDelegate.create().handle(request);
  }

  @Override
  public FactType createFactType(RequestHeader rh, CreateFactTypeRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return FactTypeCreateDelegate.builder()
            .setFactTypeHelper(new FactTypeHelper(factManager, objectManager))
            .build()
            .handle(request);
  }

  @Override
  public FactType updateFactType(RequestHeader rh, UpdateFactTypeRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactTypeUpdateDelegate.builder()
            .setFactTypeHelper(new FactTypeHelper(factManager, objectManager))
            .setFactTypeResolver(new FactTypeResolver(factManager))
            .build()
            .handle(request);
  }

  @Override
  public Object getObject(RequestHeader rh, GetObjectByIdRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return ObjectGetDelegate.create().handle(request);
  }

  @Override
  public Object getObject(RequestHeader rh, GetObjectByTypeValueRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return ObjectGetDelegate.create().handle(request);
  }

  @Override
  public ResultSet<Fact> searchObjectFacts(RequestHeader rh, SearchObjectFactsRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return ObjectSearchFactsDelegate.create().handle(request);
  }

  @Override
  public ResultSet<Object> searchObjects(RequestHeader rh, SearchObjectRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return ObjectSearchDelegate.create().handle(request);
  }

  @Override
  public Fact getFact(RequestHeader rh, GetFactByIdRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactGetByIdDelegate.create().handle(request);
  }

  @Override
  public ResultSet<Fact> searchFacts(RequestHeader rh, SearchFactRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return FactSearchDelegate.create().handle(request);
  }

  @Override
  public Fact createFact(RequestHeader rh, CreateFactRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    return FactCreateDelegate.builder()
            .setFactTypeResolver(new FactTypeResolver(factManager))
            .setObjectResolver(new ObjectResolver(objectManager, validatorFactory))
            .setFactStorageHelper(new FactStorageHelper(factManager, () -> SecurityContext.get().getCurrentUserID()))
            .build()
            .handle(request);
  }

  @Override
  public ResultSet<Fact> searchMetaFacts(RequestHeader rh, SearchMetaFactsRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactSearchMetaDelegate.create().handle(request);
  }

  @Override
  public Fact createMetaFact(RequestHeader rh, CreateMetaFactRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactCreateMetaDelegate.builder()
            .setFactTypeResolver(new FactTypeResolver(factManager))
            .setFactStorageHelper(new FactStorageHelper(factManager, () -> SecurityContext.get().getCurrentUserID()))
            .build()
            .handle(request);
  }

  @Override
  public Fact retractFact(RequestHeader rh, RetractFactRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactRetractDelegate.builder()
            .setFactTypeResolver(new FactTypeResolver(factManager))
            .setFactStorageHelper(new FactStorageHelper(factManager, () -> SecurityContext.get().getCurrentUserID()))
            .build()
            .handle(request);
  }

  @Override
  public ResultSet<FactComment> getFactComments(RequestHeader rh, GetFactCommentsRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactGetCommentsDelegate.create().handle(request);
  }

  @Override
  public FactComment createFactComment(RequestHeader rh, CreateFactCommentRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactCreateCommentDelegate.create().handle(request);
  }

  @Override
  public ResultSet<AclEntry> getFactAcl(RequestHeader rh, GetFactAclRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactGetAclDelegate.create().handle(request);
  }

  @Override
  public AclEntry grantFactAccess(RequestHeader rh, GrantFactAccessRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    return FactGrantAccessDelegate.create().handle(request);
  }

  @Override
  public ResultSet<?> traverseGraph(RequestHeader rh, TraverseByObjectIdRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, OperationTimeoutException {
    return TraverseGraphDelegate.builder()
            .setObjectSearch(ObjectSearchDelegate.create())
            .setObjectConverter(objectConverter)
            .setFactConverter(createFactConverterForGraphTraversal())
            .build()
            .handle(request);
  }

  @Override
  public ResultSet<?> traverseGraph(RequestHeader rh, TraverseByObjectTypeValueRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, OperationTimeoutException {
    return TraverseGraphDelegate.builder()
            .setObjectSearch(ObjectSearchDelegate.create())
            .setObjectConverter(objectConverter)
            .setFactConverter(createFactConverterForGraphTraversal())
            .build()
            .handle(request);
  }

  @Override
  public ResultSet<?> traverseGraph(RequestHeader rh, TraverseByObjectSearchRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, OperationTimeoutException {
    return TraverseGraphDelegate.builder()
            .setObjectSearch(ObjectSearchDelegate.create())
            .setObjectConverter(objectConverter)
            .setFactConverter(createFactConverterForGraphTraversal())
            .build()
            .handle(request);
  }

  private Function<UUID, Namespace> createNamespaceConverter() {
    // For now everything will just be part of the global namespace.
    return id -> Namespace.builder()
            .setId(GLOBAL_NAMESPACE)
            .setName("Global")
            .build();
  }

  private Function<UUID, Source> createSourceConverter() {
    // For now just return a static Source.
    return id -> Source.builder()
            .setId(id)
            .setName("Not implemented yet!")
            .build();
  }

  private Function<UUID, ObjectType> createObjectTypeByIdConverter() {
    return id -> ObjectUtils.ifNotNull(objectManager.getObjectType(id), objectTypeConverter, ObjectType.builder().setId(id).setName("N/A").build());
  }

  private Function<UUID, FactType> createFactTypeByIdConverter() {
    return id -> ObjectUtils.ifNotNull(factManager.getFactType(id), factTypeConverter, FactType.builder().setId(id).setName("N/A").build());
  }

  private Function<UUID, Object> createObjectByIdConverter() {
    return id -> ObjectUtils.ifNotNull(objectManager.getObject(id), objectConverter, Object.builder().setId(id).setValue("N/A").build());
  }

  private Function<UUID, Iterator<FactEntity>> createFactsBoundToObjectResolver() {
    return objectID -> {
      // Look up bindings for the given Object ID ...
      List<UUID> factID = objectManager.fetchObjectFactBindings(objectID)
              .stream()
              .map(ObjectFactBindingEntity::getFactID)
              .collect(Collectors.toList());
      // ... and use those to fetch the bound Facts.
      return factManager.getFacts(factID);
    };
  }

  private FactConverter createFactConverterForGraphTraversal() {
    // Need to re-define accessChecker of FactConverter because the lambda used for the FactConverter available from
    // the RequestContext is calling TiSecurityContext.get() which is not available when a Gremlin query is executed
    // in another thread. Note that this is a work-around which will be ultimately fixed when implementing service
    // request scope.
    TiSecurityContext securityContext = TiSecurityContext.get();
    return FactConverter.builder()
            .setFactTypeConverter(createFactTypeByIdConverter())
            .setOrganizationConverter(organizationResolver::resolveOrganization)
            .setSourceConverter(createSourceConverter())
            .setObjectConverter(createObjectByIdConverter())
            .setFactEntityResolver(factManager::getFact)
            .setAccessChecker(securityContext::hasReadPermission)
            .build();
  }

}
