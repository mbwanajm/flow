/*
 * Copyright 2000-2017 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.server.communication.rpc;

import java.io.Serializable;

import org.junit.Assert;
import org.junit.Test;

import com.vaadin.annotations.Id;
import com.vaadin.flow.JsonCodec;
import com.vaadin.flow.StateNode;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.nodefeature.ElementPropertyMap;
import com.vaadin.flow.nodefeature.NodeFeatureRegistry;
import com.vaadin.flow.template.angular.InlineTemplate;
import com.vaadin.shared.JsonConstants;
import com.vaadin.ui.AngularTemplateTest.H1TestComponent;
import com.vaadin.ui.ComponentTest.TestComponent;
import com.vaadin.ui.UI;

import elemental.json.Json;
import elemental.json.JsonObject;

public class MapSyncRpcHandlerTest {

    private static final String NEW_VALUE = "newValue";
    private static final String DUMMY_EVENT = "dummy-event";
    private static final String TEST_PROPERTY = "test-property";

    public static class TemplateUsingStreamConstructor extends InlineTemplate {

        @Id("header")
        protected H1TestComponent header;

        public TemplateUsingStreamConstructor() {
            super("<div><h1 id='header'>Header</h1>@child@<div id='footer'></div></div>");
        }

    }

    @Test
    public void templateSynchronizeRootElement() throws Exception {
        TemplateUsingStreamConstructor t = new TemplateUsingStreamConstructor();
        Element element = t.getElement();
        element.synchronizeProperty(TEST_PROPERTY, DUMMY_EVENT);
        UI ui = new UI();
        ui.add(t);
        Assert.assertFalse(element.hasProperty(TEST_PROPERTY));
        sendSynchronizePropertyEvent(element, ui, TEST_PROPERTY, NEW_VALUE);
        Assert.assertTrue(element.hasProperty(TEST_PROPERTY));
        Assert.assertEquals(NEW_VALUE, element.getProperty(TEST_PROPERTY));
    }

    @Test
    public void templateSynchronizeNonRootElement() throws Exception {
        TemplateUsingStreamConstructor t = new TemplateUsingStreamConstructor();
        Element element = t.header.getElement();
        element.synchronizeProperty(TEST_PROPERTY, DUMMY_EVENT);
        UI ui = new UI();
        ui.add(t);
        Assert.assertFalse(element.hasProperty(TEST_PROPERTY));
        sendSynchronizePropertyEvent(element, ui, TEST_PROPERTY, NEW_VALUE);
        Assert.assertTrue(element.hasProperty(TEST_PROPERTY));
        Assert.assertEquals(NEW_VALUE, element.getProperty(TEST_PROPERTY));
    }

    @Test
    public void testSynchronizeProperty() throws Exception {
        TestComponent c = new TestComponent();
        Element element = c.getElement();
        UI ui = new UI();
        ui.add(c);
        Assert.assertFalse(element.hasProperty(TEST_PROPERTY));
        sendSynchronizePropertyEvent(element, ui, TEST_PROPERTY, "value1");
        Assert.assertEquals("value1", element.getPropertyRaw(TEST_PROPERTY));
        sendSynchronizePropertyEvent(element, ui, TEST_PROPERTY, "value2");
        Assert.assertEquals("value2", element.getPropertyRaw(TEST_PROPERTY));
    }

    private static void sendSynchronizePropertyEvent(Element element, UI ui,
            String eventType, Serializable value) throws Exception {
        new MapSyncRpcHandler().handle(ui,
                createSyncPropertyInvocation(element, eventType, value));
    }

    private static JsonObject createSyncPropertyInvocation(Element element,
            String property, Serializable value) {
        StateNode node = EventRpcHandlerTest.getInvocationNode(element);
        // Copied from ServerConnector
        JsonObject message = Json.createObject();
        message.put(JsonConstants.RPC_NODE, node.getId());
        message.put(JsonConstants.RPC_FEATURE,
                NodeFeatureRegistry.getId(ElementPropertyMap.class));
        message.put(JsonConstants.RPC_PROPERTY, property);
        message.put(JsonConstants.RPC_PROPERTY_VALUE,
                JsonCodec.encodeWithoutTypeInfo(value));

        return message;
    }
}