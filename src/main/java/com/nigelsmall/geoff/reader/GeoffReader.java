/*
 * Copyright 2013-2014, Nigel Small
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nigelsmall.geoff.reader;

import com.nigelsmall.geoff.AbstractNode;
import com.nigelsmall.geoff.AbstractRelationship;
import com.nigelsmall.geoff.Subgraph;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

public class GeoffReader {

    final private Logger logger = LoggerFactory.getLogger(GeoffReader.class);
    private int lineNo;
    private int column;

    final private static class UnexpectedEndOfData extends IOException {}

    final private static ObjectMapper mapper = new ObjectMapper();

    final protected static int NULL = -2;
    final protected static int END_OF_DATA = -1;

    private BufferedReader reader;
    private int peeked;

    public GeoffReader(Reader reader) {
        this.reader = new BufferedReader(reader);
        this.peeked = NULL;
    }

    public GeoffReader(String string) {
        this(new StringReader(string));
    }

    private char read() throws IOException {
        int ch;
        if (this.peeked == NULL) {
            column++;
            ch = this.reader.read();
        } else {
            ch = this.peeked;
            this.peeked = NULL;
        }
        if (ch == END_OF_DATA)
            throw new UnexpectedEndOfData();
        else
            return (char)ch;
    }

    private Character peek() throws IOException {
        if (this.peeked == NULL) {
            column++;
            this.peeked = this.reader.read();
        }
        if (this.peeked == END_OF_DATA) {
            return null;
        } else {
            return (char)this.peeked;
        }
    }

    private Character readChar(char ch) throws IOException {
        if (this.nextCharEquals(ch)) {
            return this.read();
        } else {
            String msg = "Unexpected character";
            throw error(msg);
        }
    }

    private GeoffReaderException error(String msg) {
        return new GeoffReaderException(msg,lineNo,column-1);
    }

    private String readUntil(char terminator) throws IOException {
        StringBuilder s = new StringBuilder(200);
        char ch = '\0';
        while (this.hasMore() && ch != terminator) {
            ch = this.read();
            s.append(ch);
        }
        return s.toString();
    }

    private String readUntil(String terminator) throws IOException {
        StringBuilder s = new StringBuilder(200);
        char terminatorChar = terminator.charAt(terminator.length() - 1);
        while (this.hasMore() && !s.toString().endsWith(terminator)) {
            s.append(this.readUntil(terminatorChar));
        }
        return s.toString();
    }

    private boolean nextCharEquals(char ch) throws IOException {
        Character nextChar = this.peek();
        return nextChar != null && nextChar == ch;
    }

    public boolean hasMore() throws IOException {
        return this.peek() != null;
    }

    private List readArray() throws IOException {
        this.readChar('[');
        this.readWhitespace();
        if (this.nextCharEquals(']')) {
            this.readChar(']');
            return new ArrayList<Object>();
        }
        if (this.nextCharEquals('"')) {
            ArrayList<Object> items = new ArrayList<>();
            items.add(this.readString());
            this.readWhitespace();
            while (this.nextCharEquals(',')) {
                this.readChar(',');
                this.readWhitespace();
                items.add(this.readString());
                this.readWhitespace();
            }
            this.readChar(']');
            return items;
        } else if (this.nextCharEquals('-') || Character.isDigit(this.peek())) {
            ArrayList<Object> integerItems = new ArrayList<>();
            ArrayList<Object> doubleItems = new ArrayList<>();
            Number n = this.readNumber();
            doubleItems.add(n.doubleValue());
            if (n instanceof Integer) {
                integerItems.add(n.intValue());
            }
            this.readWhitespace();
            while (this.nextCharEquals(',')) {
                this.readChar(',');
                this.readWhitespace();
                n = this.readNumber();
                doubleItems.add(n.doubleValue());
                if (n instanceof Integer) {
                    integerItems.add(n.intValue());
                }
                this.readWhitespace();
            }
            this.readChar(']');
            if (integerItems.size() == doubleItems.size()) {
                return integerItems;
            } else {
                return doubleItems;
            }
        } else if (this.nextCharEquals('t') || this.nextCharEquals('f')) {
            ArrayList<Object> items = new ArrayList<>();
            items.add(this.readBoolean());
            this.readWhitespace();
            while (this.nextCharEquals(',')) {
                this.readChar(',');
                this.readWhitespace();
                items.add(this.readBoolean());
                this.readWhitespace();
            }
            this.readChar(']');
            return items;
        } else {
            throw error("Disarray");
        }
    }

    private String readArrow() throws IOException {
        if (this.nextCharEquals('<')) {
            this.readChar('<');
            this.readChar('-');
            return "<-";
        } else if (this.nextCharEquals('-')) {
            this.readChar('-');
            if (this.nextCharEquals('>')) {
                this.readChar('>');
                return "->";
            } else {
                return "-";
            }
        } else {
            throw error("Broken arrow");
        }
    }

    private boolean readBoolean() throws IOException {
        if (this.nextCharEquals('t')) {
            readChar('t');
            readChar('r');
            readChar('u');
            readChar('e');
            return true;
        } else if (this.nextCharEquals('f')) {
            readChar('f');
            readChar('a');
            readChar('l');
            readChar('s');
            readChar('e');
            return false;
        } else {
            throw error("Cannot establish truth");
        }
    }

    private void readBoundary() throws IOException {
        this.readChar('~');
        this.readChar('~');
        this.readChar('~');
        this.readChar('~');
        while (this.nextCharEquals('~')) {
            this.readChar('~');
        }
    }

    private String readComment() throws IOException {
        this.readChar('/');
        this.readChar('*');
        String comment = this.readUntil("*/");
        return comment.substring(0, comment.length() - 2).trim();
    }

    private void readKeyValuePairInto(HashMap<String, Object> map) throws IOException {
        String key = this.readName();
        this.readWhitespace();
        this.readChar(':');
        this.readWhitespace();
        Object value = this.readValue();
        map.put(key, value);
    }

    private String[] readLabelsInto(HashSet<String> labels) throws IOException {
        String[] labelAndKey = new String[] {null, null};
        while (this.nextCharEquals(':')) {
            this.readChar(':');
            String label = this.readName();
            labels.add(label);
            if (this.nextCharEquals('!')) {
                labelAndKey[0] = label;
                this.readChar('!');
                labelAndKey[1] = this.readName();
            }
        }
        return labelAndKey;
    }

    private String readName() throws IOException {
        if (this.nextCharEquals('"')) {
            return this.readString();
        } else {
            StringBuilder s = new StringBuilder();
            while (this.hasMore() && (Character.isLetterOrDigit(this.peek()) || this.nextCharEquals('_'))) {
                s.append(this.read());
            }
            return s.toString();
        }
    }

    private AbstractNode readNode() throws IOException {
        String name;
        HashSet<String> labels;
        HashMap<String, Object> properties;
        String[] labelAndKey = new String[] {null, null};
        this.readChar('(');
        this.readWhitespace();
        if (this.nextCharEquals(')')) {
            name = null;
            labels = null;
            properties = null;
        } else if (this.nextCharEquals(':')) {
            name = null;
            labels = new HashSet<>();
            labelAndKey = this.readLabelsInto(labels);
            this.readWhitespace();
            if (this.nextCharEquals('{')) {
                properties = this.readPropertyMap();
            } else {
                properties = null;
            }
        } else if (this.nextCharEquals('{')) {
            name = null;
            labels = null;
            properties = this.readPropertyMap();
        } else {
            name = this.readName();
            this.readWhitespace();
            if (this.nextCharEquals(':')) {
                labels = new HashSet<>();
                labelAndKey = this.readLabelsInto(labels);
            } else {
                labels = null;
            }
            this.readWhitespace();
            if (this.nextCharEquals('{')) {
                properties = this.readPropertyMap();
            } else {
                properties = null;
            }
        }
        this.readWhitespace();
        this.readChar(')');
        AbstractNode node = new AbstractNode(name, labels, properties);
        node.setUnique(labelAndKey[0], labelAndKey[1]);
        return node;
    }

    private Number readNumber() throws IOException {
        StringBuilder s = new StringBuilder(20);
        boolean isReal = false;
        if (this.nextCharEquals('-')) {
            s.append(this.read());
        }
        while (Character.isDigit(this.peek())) {
            s.append(this.read());
        }
        if (this.nextCharEquals('.')) {
            isReal = true;
            s.append(this.read());
            while (Character.isDigit(this.peek())) {
                s.append(this.read());
            }
        }
        if (this.nextCharEquals('E') || this.nextCharEquals('e')) {
            isReal = true;
            s.append(this.read());
            if (this.nextCharEquals('+') || this.nextCharEquals('-')) {
                s.append(this.read());
            }
            while (Character.isDigit(this.peek())) {
                s.append(this.read());
            }
        }
        if (isReal) {
            return Double.parseDouble(s.toString());
        } else {
            return Integer.parseInt(s.toString());
        }
    }

    private HashMap<String, Object> readPropertyMap() throws IOException {
        HashMap<String, Object> properties = new HashMap<>();
        this.readChar('{');
        this.readWhitespace();
        if (!this.nextCharEquals('}')) {
            this.readKeyValuePairInto(properties);
            this.readWhitespace();
            while (this.nextCharEquals(',')) {
                this.readChar(',');
                this.readWhitespace();
                this.readKeyValuePairInto(properties);
                this.readWhitespace();
            }

        }
        this.readChar('}');
        return properties;
    }

    private AbstractRelationship readRelationshipBox() throws IOException {
        this.readChar('[');
        this.readWhitespace();
        if (this.nextCharEquals(':')) {
            // read and ignore relationship name, if present
            this.readName();
            this.readWhitespace();
        }
        this.readChar(':');
        String type = this.readName();
        boolean unique;
        String uniqueKey = null;
        if (this.nextCharEquals('!')) {
            this.readChar('!');
            unique = true;
            if (this.hasMore()) {
                char ch = this.peek();
                if (!Character.isWhitespace(ch) && ch != ']' && ch != '{') {
                    uniqueKey = this.readName();
                }
            }
        } else {
            unique = false;
        }
        this.readWhitespace();
        AbstractRelationship rel;
        Map<String, Object> properties = null;
        if (this.nextCharEquals('{')) {
            properties = this.readPropertyMap();
            this.readWhitespace();
        }
        if (uniqueKey == null) {
            rel = new AbstractRelationship(null, type, properties, null, unique);
        } else {
            rel = new AbstractRelationship(null, type, properties, null, uniqueKey);
        }
        this.readWhitespace();
        this.readChar(']');
        return rel;
    }

    /** Reads a JSON formatted string
     */
    private String readString() throws IOException {
        StringBuilder s = new StringBuilder(200);
        s.append(this.read());
        boolean endOfString = false;
        while (this.hasMore() && !endOfString) {
            s.append(this.readUntil('"'));
            endOfString = !s.toString().endsWith("\\\"");
        }
        try {
            return mapper.readValue(s.toString(), String.class);
        } catch (IOException e) {
            throw error("Unable to parse JSON string");
        }
    }

    private Object readValue() throws IOException {
        Object value;
        if (this.nextCharEquals('[')) {
            List listValue = this.readArray();
            int listValueSize = listValue.size();
            if (listValueSize == 0) {
                value = new Object[0];
            } else if (listValue.get(0) instanceof String) {
                value = listValue.toArray(new String[listValueSize]);
            } else if (listValue.get(0) instanceof Integer) {
                value = listValue.toArray(new Integer[listValueSize]);
            } else if (listValue.get(0) instanceof Double) {
                value = listValue.toArray(new Double[listValueSize]);
            } else if (listValue.get(0) instanceof Boolean) {
                value = listValue.toArray(new Boolean[listValueSize]);
            } else {
                throw error("Unexpected array type");
            }
        } else if (this.nextCharEquals('"')) {
            value = this.readString();
        } else if (this.nextCharEquals('-') || Character.isDigit(this.peek())) {
            value = this.readNumber();
        } else if (this.nextCharEquals('t') || this.nextCharEquals('f')) {
            value = this.readBoolean();
        } else if (this.nextCharEquals('n')) {
            this.readChar('n');
            this.readChar('u');
            this.readChar('l');
            this.readChar('l');
            value = null;
        } else {
            throw error("Unexpected character");
        }
        return value;
    }

    public String readWhitespace() throws IOException {
        StringBuilder builder = new StringBuilder(20);
        while (this.hasMore() && Character.isWhitespace(this.peek())) {
            char ch = this.read();
            builder.append(ch);
            if (ch == '\n') {
                lineNo ++;
                column = 0;
            }
        }
        return builder.toString();
    }

    public Subgraph readSubgraph() throws IOException {
        logger.info("Reading subgraph...");
        long t0 = System.currentTimeMillis();
        Subgraph subgraph = new Subgraph();
        boolean endOfSubgraph = false;
        this.readWhitespace();
        while (this.hasMore() && !endOfSubgraph) {
            if(this.nextCharEquals('(')) {
                AbstractNode node = this.readNode();
                ArrayList<AbstractRelationship> relationships = new ArrayList<>();
                while (this.nextCharEquals('<') || this.nextCharEquals('-')) {
                    String arrow1 = this.readArrow();
                    AbstractRelationship rel = this.readRelationshipBox();
                    String arrow2 = this.readArrow();
                    AbstractNode otherNode = this.readNode();
                    if ("-".equals(arrow1) && "-".equals(arrow2)) {
                        throw error("Lack of direction");
                    }
                    if ("<-".equals(arrow1)) {
                        relationships.add(new AbstractRelationship(otherNode, rel.getType(), rel.getProperties(), node, rel.isUnique()));
                    }
                    if ("->".equals(arrow2)) {
                        relationships.add(new AbstractRelationship(node, rel.getType(), rel.getProperties(), otherNode, rel.isUnique()));
                    }
                    node = otherNode;
                }
                this.readWhitespace();
                Map<String, Object> properties = null;
                if (this.nextCharEquals('{')) {
                    properties = this.readPropertyMap();
                }
                if (relationships.size() > 0) {
                    for (AbstractRelationship rel : relationships) {
                        rel.mergeProperties(properties);
                        subgraph.addRelationship(rel);
                    }
                } else {
                    node.mergeProperties(properties);
                    subgraph.mergeNode(node);
                }
            } else if(this.nextCharEquals(':')) {
                this.readChar(':');
                this.readWhitespace();
                String label = this.readName();
                this.readWhitespace();
                this.readChar(':');
                this.readWhitespace();
                String key = null;
                if (!this.nextCharEquals('=')) {
                    key = this.readName();
                    this.readWhitespace();
                    this.readChar(':');
                    this.readWhitespace();
                }
                this.readChar('=');
                this.readChar('>');
                AbstractNode node = this.readNode();
                subgraph.mergeNode(node).setUnique(label, key);
            } else  if(this.nextCharEquals('/')) {
                subgraph.addComment(this.readComment());
            } else if(this.nextCharEquals('~')) {
                this.readBoundary();
                endOfSubgraph = true;
            } else if(this.hasMore()) {
                throw error("Unexpected character " + this.peek());
            }
            this.readWhitespace();
        }
        // finish read
        long t1 = System.currentTimeMillis() - t0;
        logger.info(String.format("Read subgraph with %d nodes and %d relationships in %dms",
                subgraph.order(), subgraph.size(), t1));
        return subgraph;
    }

}
