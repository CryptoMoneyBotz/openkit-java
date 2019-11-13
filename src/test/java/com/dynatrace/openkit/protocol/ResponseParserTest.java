/**
 * Copyright 2018-2019 Dynatrace LLC
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dynatrace.openkit.protocol;

import com.dynatrace.openkit.util.json.parser.ParserException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

public class ResponseParserTest {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void parsingEmptyStringThrowsException() throws ParserException {
        // given
        String input = "";
        expectedException.expect(ParserException.class);

        // when
        ResponseParser.parseResponse(input);
    }

    @Test
    public void parsingArbitraryResponseThrowsException() throws ParserException {
        // given
        String input = "some response text";
        expectedException.expect(ParserException.class);

        // when
        ResponseParser.parseResponse(input);
    }

    @Test
    public void parseKeyValueResponseWorks() throws ParserException {
        // given
        String input = "type=m&bl=17&id=18&cp=0";

        // when
        Response obtained = ResponseParser.parseResponse(input);

        // then
        assertThat(obtained, notNullValue());
        assertThat(obtained.getMaxBeaconSizeInBytes(), is(17 * 1024));
        assertThat(obtained.getServerId(), is(18));
        assertThat(obtained.isCapture(), is(false));
    }

    @Test
    public void parseJsonResponseWorks() throws ParserException {
        // given
        StringBuilder inputBuilder = new StringBuilder();
        inputBuilder.append("{");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_AGENT_CONFIG).append("\": {");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_MAX_BEACON_SIZE_IN_KB).append("\": 17");
        inputBuilder.append("},");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_APP_CONFIG).append("\": {");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_CAPTURE).append("\": 0");
        inputBuilder.append("},");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_DYNAMIC_CONFIG).append("\": {");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_SERVER_ID).append("\": 18");
        inputBuilder.append("},");
        inputBuilder.append("\"").append(JsonResponseParser.RESPONSE_KEY_TIMESTAMP_IN_MILLIS).append("\": 19");
        inputBuilder.append("}");

        // when
        Response obtained = ResponseParser.parseResponse(inputBuilder.toString());

        // then
        assertThat(obtained, notNullValue());
        assertThat(obtained.getMaxBeaconSizeInBytes(), is(17 * 1024));
        assertThat(obtained.getServerId(), is(18));
        assertThat(obtained.isCapture(), is(false));
        assertThat(obtained.getTimestampInMilliseconds(), is(19L));
    }
}
