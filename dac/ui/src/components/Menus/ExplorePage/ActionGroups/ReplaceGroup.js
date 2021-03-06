/*
 * Copyright (C) 2017 Dremio Corporation
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
import { Component, PropTypes } from 'react';
import Radium from 'radium';
import pureRender from 'pure-render-decorator';
import Divider from 'material-ui/Divider';

import { TEXT } from 'constants/DataTypes';
import { REPLACEABLE_TYPES } from 'constants/columnTypeGroups';

import ColumnMenuItem from './../ColumnMenus/ColumnMenuItem';

@Radium
@pureRender
export default class ReplaceGroup extends Component {
  static propTypes = {
    makeTransform: PropTypes.func.isRequired,
    isAvailable: PropTypes.func.isRequired,
    columnType: PropTypes.string
  }

  renderMenuItems(columnType) {
    return [
      <ColumnMenuItem
        columnType={columnType}
        actionType='EXTRACT_TEXT'
        title={la('Extract Text...')}
        availableTypes={[TEXT]}
        onClick={this.props.makeTransform}
      />,
      <ColumnMenuItem
        columnType={columnType}
        actionType='REPLACE_TEXT'
        title={la('Replace Text...')}
        availableTypes={[TEXT]}
        onClick={this.props.makeTransform}
      />,
      <ColumnMenuItem
        columnType={columnType}
        actionType='REPLACE'
        title={la('Replace...')}
        availableTypes={REPLACEABLE_TYPES}
        onClick={this.props.makeTransform}
      />,
      <ColumnMenuItem
        columnType={columnType}
        actionType='SPLIT'
        title={la('Split...')}
        availableTypes={[TEXT]}
        onClick={this.props.makeTransform}
      />
    ];
  }

  render() {
    const { columnType, isAvailable } = this.props;
    const menuItems = this.renderMenuItems(columnType);
    const shouldShowDivider = isAvailable(menuItems, columnType);
    return (
      <div>
        {menuItems}
        {shouldShowDivider && <Divider style={{marginTop: 5, marginBottom: 5}}/>}
      </div>
    );
  }
}
