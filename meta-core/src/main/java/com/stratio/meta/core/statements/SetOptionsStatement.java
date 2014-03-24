/*
 * Stratio Meta
 *
 * Copyright (c) 2014, Stratio, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package com.stratio.meta.core.statements;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Statement;
import com.stratio.meta.common.result.MetaResult;
import com.stratio.meta.core.metadata.MetadataManager;
import com.stratio.meta.core.structures.Consistency;
import com.stratio.meta.core.utils.DeepResult;
import com.stratio.meta.core.utils.MetaStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SetOptionsStatement extends MetaStatement {

    private Consistency consistency;
    private boolean analytics;
    private List<Boolean> optionsCheck;

    public SetOptionsStatement(boolean analytics, Consistency consistency, List<Boolean> optionsCheck) {
        this.consistency = consistency;
        this.analytics = analytics;
        this.optionsCheck = new ArrayList<>();
        this.optionsCheck.addAll(optionsCheck);
    }

    public Consistency getConsistency() {
        return consistency;
    }

    public void setConsistency(Consistency consistency) {
        this.consistency = consistency;
    }

    public boolean isAnalytics() {
        return analytics;
    }

    public void setAnalytics(boolean analytics) {
        this.analytics = analytics;
    }        

    public List<Boolean> getOptionsCheck() {
        return optionsCheck;
    }

    public void setOptionsCheck(List<Boolean> optionsCheck) {
        this.optionsCheck = optionsCheck;
    }        
    
    @Override
    public String toString() {
        //System.out.println("optionsCheck="+optionsCheck.toString());
        StringBuilder sb = new StringBuilder("Set options ");
        if(optionsCheck.get(0)){
            sb.append("analytics=").append(analytics);            
            if(optionsCheck.get(1)){
                sb.append(" AND consistency=").append(consistency);
            }
        } else {
            if(optionsCheck.get(1)){
                sb.append("consistency=").append(consistency);
            }
        }        
        return sb.toString();
    }

    /** {@inheritDoc} */
    @Override
    public MetaResult validate(MetadataManager metadata, String targetKeyspace) {
        return null;
    }

    @Override
    public String getSuggestion() {
        return this.getClass().toString().toUpperCase()+" EXAMPLE";
    }

    @Override
    public String translateToCQL() {
        return this.toString();
    }
    
//    @Override
//    public String parseResult(ResultSet resultSet) {
//        return "\t"+resultSet.toString();
//    }
    
    @Override
    public Statement getDriverStatement() {
        Statement statement = null;
        return statement;
    }
    
    @Override
    public DeepResult executeDeep() {
        return new DeepResult("", new ArrayList<>(Arrays.asList("Not supported yet")));
    }
    
    @Override
    public List<MetaStep> getPlan() {
        ArrayList<MetaStep> steps = new ArrayList<>();
        return steps;
    }
    
}