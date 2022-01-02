/**
 * Copyright (c) 2012 - 2021 Paremus Ltd., Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors:
 * 		Paremus Ltd. - initial API and implementation
 *      Data In Motion
 */
/*
 Copyright 2008-2011 the original author or authors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.paremus.dosgi.dsw.net.serialization;

import java.io.Serializable;


public class WrapperPojo implements Serializable {
    private static final long serialVersionUID = 1L;

    public Boolean booleanField;
    public Byte byteField;
    public Short shortField;
    public Character charField;
    public Integer intField;
    public Float floatField;
    public Long longField;
    public Double doubleField;
    public String stringField;

    public WrapperPojo(Boolean booleanField,
                       Byte byteField,
                       Short shortField,
                       Character charField,
                       Integer intField,
                       Float floatField,
                       Long longField,
                       Double doubleField,
                       String stringField) {
        this.booleanField = booleanField;
        this.byteField = byteField;
        this.shortField = shortField;
        this.charField = charField;
        this.intField = intField;
        this.floatField = floatField;
        this.longField = longField;
        this.doubleField = doubleField;
        this.stringField = stringField;
    }

}
