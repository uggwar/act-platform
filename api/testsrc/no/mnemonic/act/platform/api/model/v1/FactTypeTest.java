package no.mnemonic.act.platform.api.model.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FactTypeTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void testEncodeFactType() {
    FactType type = createFactType();
    JsonNode root = mapper.valueToTree(type);
    assertEquals(type.getId().toString(), root.get("id").textValue());
    assertTrue(root.get("namespace").isObject());
    assertEquals(type.getName(), root.get("name").textValue());
    assertEquals(type.getValidator(), root.get("validator").textValue());
    assertEquals(type.getValidatorParameter(), root.get("validatorParameter").textValue());
    assertEquals(type.getEntityHandler(), root.get("entityHandler").textValue());
    assertEquals(type.getEntityHandlerParameter(), root.get("entityHandlerParameter").textValue());
    assertTrue(root.get("relevantObjectBindings").isArray());
    assertTrue(root.get("relevantObjectBindings").get(0).get("sourceObjectType").isObject());
    assertTrue(root.get("relevantObjectBindings").get(0).get("destinationObjectType").isObject());
    assertTrue(root.get("relevantObjectBindings").get(0).get("bidirectionalBinding").booleanValue());
  }

  @Test
  public void testEncodeFactTypeInfo() {
    FactType.Info type = createFactType().toInfo();
    JsonNode root = mapper.valueToTree(type);
    assertEquals(type.getId().toString(), root.get("id").textValue());
    assertEquals(type.getName(), root.get("name").textValue());
  }

  private FactType createFactType() {
    return FactType.builder()
            .setId(UUID.randomUUID())
            .setNamespace(Namespace.builder().setId(UUID.randomUUID()).setName("namespace").build())
            .setName("factType")
            .setValidator("validator")
            .setValidatorParameter("validatorParameter")
            .setEntityHandler("entityHandler")
            .setEntityHandlerParameter("entityHandlerParameter")
            .addRelevantObjectBinding(new FactType.FactObjectBindingDefinition(createObjectType(), createObjectType(), true))
            .build();
  }

  private ObjectType.Info createObjectType() {
    return ObjectType.builder()
            .setId(UUID.randomUUID())
            .setName("objectType")
            .build()
            .toInfo();
  }

}
