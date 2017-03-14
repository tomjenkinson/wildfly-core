/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cli.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandHandler;
import org.jboss.as.cli.CommandLineCompleter;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.CommandRegistry;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.DefaultCompleter;
import org.jboss.as.cli.impl.DefaultCompleter.CandidatesProvider;
import org.jboss.as.cli.operation.OperationRequestAddress;
import org.jboss.as.cli.operation.OperationRequestCompleter;
import org.jboss.as.cli.operation.ParsedCommandLine;
import org.jboss.as.cli.operation.impl.DefaultCallbackHandler;
import org.jboss.as.cli.parsing.ParserUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

/**
 *
 * @author Alexey Loubyansky
 */
public class CommandCommandHandler extends CommandHandlerWithHelp {

    private final ArgumentWithValue action = new ArgumentWithValue(this, new SimpleTabCompleter(new String[]{"add", "list", "remove"}), 0, "--action");
    private final ArgumentWithValue nodePath;
    private final ArgumentWithValue idProperty;
    private final ArgumentWithValue commandName;

    private final CommandRegistry cmdRegistry;

    private DefaultCallbackHandler callback;

    public CommandCommandHandler(CommandRegistry cmdRegistry) {
        super("command", true);
        this.cmdRegistry = cmdRegistry;

        action.addCantAppearAfter(helpArg);

        nodePath = new ArgumentWithValue(this, new CommandLineCompleter(){
            @Override
            public int complete(CommandContext ctx, String buffer, int cursor, List<String> candidates) {
                int offset = 0;
                int result = OperationRequestCompleter.ARG_VALUE_COMPLETER.complete(ctx, buffer, cursor + offset, candidates) - offset;
                if(result < 0) {
                    return result;
                }
                return result;
            }}, "--node-type") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                return "add".equals(action.getValue(ctx.getParsedCommandLine())) && super.canAppearNext(ctx);
            }
        };

        idProperty = new ArgumentWithValue(this, new DefaultCompleter(new CandidatesProvider(){
            @Override
            public List<String> getAllCandidates(CommandContext ctx) {
                List<Property> props;
                try {
                    props = getNodeProperties(ctx);
                } catch (CommandFormatException e) {
                    return Collections.emptyList();
                }

                final List<String> candidates = new ArrayList<String>();
                for(Property prop : props) {
                    final ModelNode value = prop.getValue();
                    if(value.has("access-type") && "read-only".equals(value.get("access-type").asString())) {
                        candidates.add(prop.getName());
                    }
                }
                return candidates;
            }}), "--property-id");
        idProperty.addRequiredPreceding(nodePath);

        commandName = new ArgumentWithValue(this, new DefaultCompleter(new CandidatesProvider(){

            private final DefaultCallbackHandler callback = new DefaultCallbackHandler();

            @Override
            public List<String> getAllCandidates(CommandContext ctx) {

                final String actionName = action.getValue(ctx.getParsedCommandLine());
                if(actionName == null) {
                    return Collections.emptyList();
                }

                if (actionName.equals("add")) {
                   final String thePath = nodePath.getValue(ctx.getParsedCommandLine());
                   if (thePath == null) {
                      return Collections.emptyList();
                   }

                   callback.reset();
                   try {
                       ParserUtil.parseOperationRequest(thePath, callback);
                   } catch (CommandFormatException e) {
                       return Collections.emptyList();
                   }

                   OperationRequestAddress typeAddress = callback.getAddress();
                   if (!typeAddress.endsOnType()) {
                       return Collections.emptyList();
                   }
                   return Collections.singletonList(typeAddress.getNodeType());
               }

                if (actionName.equals("remove")) {
                    return getExistingCommands();
                }
                return Collections.emptyList();
            }}), "--command-name") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                ParsedCommandLine args = ctx.getParsedCommandLine();
                if(isPresent(args)) {
                    return false;
                }
                final String actionStr = action.getValue(args);
                if(actionStr == null) {
                    return false;
                }
                if("add".equals(actionStr)) {
                    return nodePath.isValueComplete(args);
                }
                if("remove".equals(actionStr)) {
                    return true;
                }
                return false;
            }
        };
    }

    /* (non-Javadoc)
     * @see org.jboss.as.cli.handlers.CommandHandlerWithHelp#doHandle(org.jboss.as.cli.CommandContext)
     */
    @Override
    protected void doHandle(CommandContext ctx) throws CommandLineException {

        final ParsedCommandLine args = ctx.getParsedCommandLine();
        final String action = this.action.getValue(args);
        if(action == null) {
            throw new CommandFormatException("Command is missing.");
        }

        if(action.equals("list")) {
            ctx.printColumns(getExistingCommands());
            return;
        }

        if(action.equals("add")) {
            final String nodePath = this.nodePath.getValue(args, true);
            final String propName = this.idProperty.getValue(args, false);
            final String cmdName = this.commandName.getValue(args, true);
            validateInput(ctx, nodePath, propName);

            if(cmdRegistry.getCommandHandler(cmdName) != null) {
                throw new CommandFormatException("Command '" + cmdName + "' already registered.");
            }
            cmdRegistry.registerHandler(new GenericTypeOperationHandler(cmdName, ctx, nodePath, propName), cmdName);
            return;
        }

        if(action.equals("remove")) {
            final String cmdName = this.commandName.getValue(args, true);
            CommandHandler handler = cmdRegistry.getCommandHandler(cmdName);
            if(!(handler instanceof GenericTypeOperationHandler)) {
                throw new CommandFormatException("Command '" + cmdName + "' is not a generic type command.");
            }
            cmdRegistry.remove(cmdName);
            return;
        }

        throw new CommandFormatException("Unexpected action: " + action);
    }

    protected List<String> getExistingCommands() {
        final List<String> commands = new ArrayList<String>();
        for(String cmd : cmdRegistry.getTabCompletionCommands()) {
            if(cmdRegistry.getCommandHandler(cmd) instanceof GenericTypeOperationHandler) {
                commands.add(cmd);
            }
        }
        return commands;
    }

    protected List<Property> getNodeProperties(CommandContext ctx) throws CommandFormatException {
        final ModelNode request = initRequest(ctx);
        request.get(Util.OPERATION).set(Util.READ_RESOURCE_DESCRIPTION);
        ModelNode result;
        try {
            result = ctx.getModelControllerClient().execute(request);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        if(!result.hasDefined(Util.RESULT)) {
            return Collections.emptyList();
        }
        result = result.get(Util.RESULT);
        if(!result.hasDefined(Util.ATTRIBUTES)) {
            return Collections.emptyList();
        }
        return result.get(Util.ATTRIBUTES).asPropertyList();
    }

    protected ModelNode initRequest(CommandContext ctx) throws CommandFormatException {
        ModelNode request = new ModelNode();
        ModelNode address = request.get(Util.ADDRESS);

        final String type = nodePath.getValue(ctx.getParsedCommandLine());
        if(callback == null) {
            callback = new DefaultCallbackHandler();
        } else {
            callback.reset();
        }
        ParserUtil.parseOperationRequest(type, callback);

        OperationRequestAddress typeAddress = callback.getAddress();
        if(!typeAddress.endsOnType()) {
            return null;
        }

        final String typeName = typeAddress.toParentNode().getType();
        for(OperationRequestAddress.Node node : typeAddress) {
            address.add(node.getType(), node.getName());
        }
        address.add(typeName, "?");
        return request;
    }

    protected void validateInput(CommandContext ctx, String typePath, String propertyName) throws CommandFormatException {

        ModelNode request = new ModelNode();
        ModelNode address = request.get(Util.ADDRESS);

        if(callback == null) {
            callback = new DefaultCallbackHandler();
        } else {
            callback.reset();
        }

        try {
            ParserUtil.parseOperationRequest(typePath, callback);
        } catch (CommandFormatException e) {
            throw new CommandFormatException("Failed to validate input: " + e.getLocalizedMessage());
        }

        OperationRequestAddress typeAddress = callback.getAddress();
        if(!typeAddress.endsOnType()) {
            throw new CommandFormatException("Node path '" + typePath + "' doesn't appear to end on a type.");
        }

        final String typeName = typeAddress.toParentNode().getType();
        for(OperationRequestAddress.Node node : typeAddress) {
            address.add(node.getType(), node.getName());
        }

        request.get(Util.OPERATION).set(Util.READ_CHILDREN_TYPES);

        ModelNode result;
        try {
            result = ctx.getModelControllerClient().execute(request);
        } catch (IOException e) {
            throw new CommandFormatException("Failed to validate input: " + e.getLocalizedMessage());
        }
        if(!result.hasDefined(Util.RESULT)) {
            throw new CommandFormatException("Failed to validate input: operation response doesn't contain result info.");
        }

        boolean pathValid = false;
        for(ModelNode typeNode : result.get(Util.RESULT).asList()) {
            if(typeNode.asString().equals(typeName)) {
                pathValid = true;
                break;
            }
        }
        if(!pathValid) {
            throw new CommandFormatException("Type '" + typeName + "' not found among child types of '" + ctx.getNodePathFormatter().format(typeAddress) + "'");
        }

        address.add(typeName, "?");
        request.get(Util.OPERATION).set(Util.READ_RESOURCE_DESCRIPTION);

        try {
            result = ctx.getModelControllerClient().execute(request);
        } catch (IOException e) {
            throw new CommandFormatException(e.getLocalizedMessage());
        }
        if(!result.hasDefined(Util.RESULT)) {
            throw new CommandFormatException("Failed to validate input: operation response doesn't contain result info.");
        }
        result = result.get(Util.RESULT);
        if(!result.hasDefined("attributes")) {
            throw new CommandFormatException("Failed to validate input: description of attributes is missing for " + typePath);
        }

        if(propertyName != null) {
            for(Property prop : result.get(Util.ATTRIBUTES).asPropertyList()) {
                if(prop.getName().equals(propertyName)) {
                    ModelNode value = prop.getValue();
                    if(value.has(Util.ACCESS_TYPE) && Util.READ_ONLY.equals(value.get(Util.ACCESS_TYPE).asString())) {
                        return;
                    }
                    throw new CommandFormatException("Property " + propertyName + " is not read-only.");
                }
            }
        } else {
            return;
        }
        throw new CommandFormatException("Property '" + propertyName + "' wasn't found among the properties of " + typePath);
    }
}
